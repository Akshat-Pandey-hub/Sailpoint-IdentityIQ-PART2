package com.keyforge.part2.reconciliation;

import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Part 1's native WorkItem reconciliation ({@code iiq.reconciliation.WorkItemReconciliation}), ported to Part 2's
 * native-shaped schema: same check, same SQL, same determination wording, same persisted values. Read-only over
 * {@code kf_identity_request_approval}, {@code kf_workitem}, {@code kf_workitem_archive}; writes only
 * {@code kf_reconciliation_finding}. Never changes source data, never marks anything deleted.
 *
 * <p><b>What it answers:</b> every distinct, non-blank {@code kf_identity_request_approval.work_item_id} is
 * <em>matched</em> when it exists as a live WorkItem ({@code kf_workitem.source_id}) or an archived one
 * ({@code kf_workitem_archive.work_item_id}), by EXPLICIT id equality only, and <em>unmatched</em> otherwise. If a
 * required table/column is absent, the finding is SKIPPED with the reason (never a false zero).
 */
public final class WorkItemReconciliation {

    static final String APPROVAL = "kf_identity_request_approval";
    static final String WORKITEM = "kf_workitem";
    static final String ARCHIVE = "kf_workitem_archive";
    public static final String CHECK_NAME = "approval_workitem_reference";

    private final String schema;
    private final ReconciliationFindingRepository findings;

    public WorkItemReconciliation(String schema) {
        this.schema = SchemaName.validate(schema);
        this.findings = new ReconciliationFindingRepository(this.schema);
    }

    public ReconciliationFindingRepository findings() {
        return findings;
    }

    /** Aggregate outcome of one pass (Part 1's fields). */
    public static final class Result {
        public boolean skipped;
        public String skipReason;
        public long liveWorkItems;
        public long workItemArchives;
        public long approvalRefs;     // distinct, non-blank work_item_id in approvals
        public long matchedLive;      // refs present as a live WorkItem
        public long matchedArchive;   // refs present as an archived WorkItem
        public long matchedEither;    // refs present in live OR archive
        public long unmatched;        // refs present in neither
        public final List<String> unmatchedSamples = new ArrayList<>();
        public String determination;  // neutral, evidence-only explanation (persisted + printed)
        public UUID findingId;        // the persisted finding
    }

    /** Part 1's neutral, evidence-only determination (never asserts a cause). */
    static String buildDetermination(Result r) {
        if (r.skipped) {
            return "Reconciliation skipped: " + r.skipReason;
        }
        if (r.approvalRefs == 0) {
            return "No approval work_item_id references present — nothing to reconcile.";
        }
        if (r.unmatched == 0) {
            return "All " + r.approvalRefs + " approval WorkItem reference(s) resolve to a live or archived WorkItem.";
        }
        if (r.workItemArchives == 0) {
            return r.unmatched + " approval WorkItem reference(s) do not resolve to a live WorkItem or "
                    + "WorkItemArchive record. The IIQ instance currently contains zero WorkItemArchive records, "
                    + "so these historical WorkItem references are unavailable in the extracted IIQ data.";
        }
        return r.unmatched + " approval WorkItem reference(s) do not resolve to a live WorkItem or "
                + "WorkItemArchive record. " + r.workItemArchives + " WorkItemArchive record(s) exist but do not "
                + "include these ids, so these historical WorkItem references are unavailable in the extracted IIQ data.";
    }

    // ---- SQL builders (Part 1's, schema-qualified) ----

    static String qualified(String schema, String table) {
        return schema + "." + table;
    }

    static String countSql(String schema, String table) {
        return "SELECT count(*) FROM " + qualified(schema, table);
    }

    static String distinctRefsSql(String schema) {
        return "SELECT count(DISTINCT work_item_id) FROM " + qualified(schema, APPROVAL)
                + " WHERE work_item_id IS NOT NULL AND work_item_id <> ''";
    }

    private static String refsCte(String schema) {
        return "SELECT DISTINCT work_item_id AS wid FROM " + qualified(schema, APPROVAL)
                + " WHERE work_item_id IS NOT NULL AND work_item_id <> ''";
    }

    static String matchedLiveSql(String schema) {
        return "SELECT count(*) FROM (" + refsCte(schema) + ") r WHERE EXISTS ("
                + "SELECT 1 FROM " + qualified(schema, WORKITEM) + " w WHERE w.source_id = r.wid)";
    }

    static String matchedArchiveSql(String schema) {
        return "SELECT count(*) FROM (" + refsCte(schema) + ") r WHERE EXISTS ("
                + "SELECT 1 FROM " + qualified(schema, ARCHIVE) + " a WHERE a.work_item_id = r.wid)";
    }

    static String matchedEitherSql(String schema) {
        return "SELECT count(*) FROM (" + refsCte(schema) + ") r WHERE "
                + "EXISTS (SELECT 1 FROM " + qualified(schema, WORKITEM) + " w WHERE w.source_id = r.wid) "
                + "OR EXISTS (SELECT 1 FROM " + qualified(schema, ARCHIVE) + " a WHERE a.work_item_id = r.wid)";
    }

    static String unmatchedSamplesSql(String schema, int limit) {
        return "SELECT r.wid FROM (" + refsCte(schema) + ") r WHERE "
                + "NOT EXISTS (SELECT 1 FROM " + qualified(schema, WORKITEM) + " w WHERE w.source_id = r.wid) "
                + "AND NOT EXISTS (SELECT 1 FROM " + qualified(schema, ARCHIVE) + " a WHERE a.work_item_id = r.wid) "
                + "ORDER BY r.wid LIMIT " + limit;
    }

    // ---- execution ----

    /** Runs the check and persists its finding for {@code runId}. Any SQL error propagates. */
    public Result reconcile(Connection conn, String runId) throws SQLException {
        Result r = new Result();
        List<String> missing = new ArrayList<>();
        if (!columnExists(conn, APPROVAL, "work_item_id")) {
            missing.add(APPROVAL + ".work_item_id (run import-identity-request-approval)");
        }
        if (!columnExists(conn, WORKITEM, "source_id")) {
            missing.add(WORKITEM + ".source_id (run import-work-item)");
        }
        if (!columnExists(conn, ARCHIVE, "work_item_id")) {
            missing.add(ARCHIVE + ".work_item_id (run import-workitem-archive)");
        }
        if (!missing.isEmpty()) {
            r.skipped = true;
            r.skipReason = "required table/column absent: " + String.join("; ", missing);
            r.determination = buildDetermination(r);
            r.findingId = persist(conn, runId, r);
            return r;
        }
        r.liveWorkItems = scalar(conn, countSql(schema, WORKITEM));
        r.workItemArchives = scalar(conn, countSql(schema, ARCHIVE));
        r.approvalRefs = scalar(conn, distinctRefsSql(schema));
        r.matchedLive = scalar(conn, matchedLiveSql(schema));
        r.matchedArchive = scalar(conn, matchedArchiveSql(schema));
        r.matchedEither = scalar(conn, matchedEitherSql(schema));
        r.unmatched = r.approvalRefs - r.matchedEither;
        if (r.unmatched > 0) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(unmatchedSamplesSql(schema, 25))) {
                while (rs.next()) {
                    r.unmatchedSamples.add(rs.getString(1));
                }
            }
        }
        r.determination = buildDetermination(r);
        r.findingId = persist(conn, runId, r);
        return r;
    }

    /** Part 1's persisted values for this check: severity SKIPPED / INFO (unmatched) / OK. */
    static ReconciliationFindingRepository.Finding finding(Result r) {
        return new ReconciliationFindingRepository.Finding(CHECK_NAME, APPROVAL, "work_item_id",
                WORKITEM + "|" + ARCHIVE, "source_id|work_item_id",
                r.skipped ? "SKIPPED" : "CHECKED", r.skipReason, r.skipped ? null : r.unmatched,
                r.unmatchedSamples, r.skipped ? "SKIPPED" : (r.unmatched > 0 ? "INFO" : "OK"), r.determination);
    }

    private UUID persist(Connection conn, String runId, Result r) throws SQLException {
        findings.ensureTargetTable(conn);
        return findings.upsert(conn, runId, finding(r), Instant.now());
    }

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}

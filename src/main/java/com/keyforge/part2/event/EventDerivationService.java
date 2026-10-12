package com.keyforge.part2.event;

import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the append-only {@code kf_event} store from the already-loaded Part 2 native tables — the event half of
 * Part 1's {@code iiq.nativeevent.NativeEventDerivationService} (same five sources, same SQL, same existence guard).
 * It calls no KF Agent endpoint and never modifies a source table. A missing source table is reported in
 * {@link Result#sourcesMissing} and contributes no events (never fabricated).
 *
 * <p>All appends run in ONE transaction: a failure rolls the whole pass back, so a failed run leaves {@code kf_event}
 * exactly as it was. Re-running is idempotent ({@code ON CONFLICT (event_id) DO NOTHING}); existing events are never
 * updated or deleted, even if their source row has since changed or disappeared.
 */
public final class EventDerivationService {

    static final String APPROVAL = "kf_identity_request_approval";
    static final String TASK_RESULT = "kf_task_result";
    static final String PROVISIONING_TXN = "kf_provisioning_txn";
    static final String WORKITEM_ARCHIVE = "kf_workitem_archive";
    static final String CERTIFICATION_ITEM = "kf_certification_item";

    private final String schema;
    private final EventRepository eventRepo;

    public EventDerivationService(String schema) {
        this.schema = SchemaName.validate(schema);
        this.eventRepo = new EventRepository(this.schema);
    }

    public String eventTable() {
        return eventRepo.table();
    }

    /** Aggregate outcome (all counts are exact, measured in this pass). */
    public static final class Result {
        public int approvalRows;
        public int taskRows;
        public int provRows;
        public int archiveRows;
        public int certItemRows;
        public final List<String> sourcesMissing = new ArrayList<>();
        public int eventsDerived;
        public int eventsInserted;
        public final Map<String, Integer> eventsByType = new LinkedHashMap<>();
        public final Map<String, Integer> insertedByType = new LinkedHashMap<>();

        /** Derived events whose event_id was already stored (unchanged source facts). */
        public int eventsAlreadyPresent() {
            return eventsDerived - eventsInserted;
        }
    }

    /** One derivation pass for {@code runId}; any SQL error rolls the pass back and propagates. */
    public Result derive(Connection conn, String runId) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Result r = deriveInTransaction(conn, runId);
            conn.commit();
            return r;
        } catch (SQLException | RuntimeException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // connection is being closed by the caller anyway
            }
        }
    }

    private Result deriveInTransaction(Connection conn, String runId) throws SQLException {
        eventRepo.ensureTargetTable(conn);
        Result r = new Result();

        List<EventDeriver.ApprovalSrc> approvals = new ArrayList<>();
        if (tableExists(conn, APPROVAL)) {
            approvals = readApprovals(conn);
            r.approvalRows = approvals.size();
        } else {
            r.sourcesMissing.add(APPROVAL);
        }
        List<EventDeriver.TaskSrc> tasks = new ArrayList<>();
        if (tableExists(conn, TASK_RESULT)) {
            tasks = readTasks(conn);
            r.taskRows = tasks.size();
        } else {
            r.sourcesMissing.add(TASK_RESULT);
        }
        List<EventDeriver.ProvSrc> provisioning = new ArrayList<>();
        if (tableExists(conn, PROVISIONING_TXN)) {
            provisioning = readProvisioning(conn);
            r.provRows = provisioning.size();
        } else {
            r.sourcesMissing.add(PROVISIONING_TXN);
        }
        List<EventDeriver.ArchiveSrc> archives = new ArrayList<>();
        if (tableExists(conn, WORKITEM_ARCHIVE)) {
            archives = readArchives(conn);
            r.archiveRows = archives.size();
        } else {
            r.sourcesMissing.add(WORKITEM_ARCHIVE);
        }
        List<EventDeriver.CertItemSrc> certItems = new ArrayList<>();
        if (tableExists(conn, CERTIFICATION_ITEM)) {
            certItems = readCertItems(conn);
            r.certItemRows = certItems.size();
        } else {
            r.sourcesMissing.add(CERTIFICATION_ITEM);
        }

        List<EventRow> events = EventDeriver.deriveAll(approvals, tasks, provisioning, archives, certItems, runId);
        r.eventsDerived = events.size();
        for (EventRow e : events) {
            r.eventsByType.merge(e.eventType(), 1, Integer::sum);
            if (eventRepo.append(conn, e)) {
                r.eventsInserted++;
                r.insertedByType.merge(e.eventType(), 1, Integer::sum);
            }
        }
        return r;
    }

    // --- read-only SQL over the native source tables (Part 1's statements) ---

    static String approvalsSql(String schema) {
        return "SELECT identityrequestapprovalid, request_source_id, owner_id, owner, state, approved, "
                + "completer, work_item_id, start_date, end_date FROM " + schema + "." + APPROVAL;
    }

    static String tasksSql(String schema) {
        return "SELECT taskresultid, name, type, completion_status, target_class, target_id, "
                + "launched_at, completed_at FROM " + schema + "." + TASK_RESULT;
    }

    static String provisioningSql(String schema) {
        return "SELECT provisioningtxnid, operation, type, status, plan_result_status, identity_name, "
                + "application_name, access_request_id, owner_id, wait_work_item_id, manual_work_item_id, "
                + "created_at FROM " + schema + "." + PROVISIONING_TXN;
    }

    static String archivesSql(String schema) {
        return "SELECT source_id, name, type, state, completer, is_signed, identity_request_id, "
                + "work_item_id, archived_ts FROM " + schema + "." + WORKITEM_ARCHIVE;
    }

    static String certItemsSql(String schema) {
        return "SELECT certificationitemid, certification_id, entity_id, identity, action_status, "
                + "action_remediation_action, action_actor_name, action_is_approved, action_is_remediation, "
                + "action_is_revoke_account, acted_upon, owner_id, action_decision_date, finished_date, completed "
                + "FROM " + schema + "." + CERTIFICATION_ITEM;
    }

    List<EventDeriver.ApprovalSrc> readApprovals(Connection conn) throws SQLException {
        List<EventDeriver.ApprovalSrc> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(approvalsSql(schema))) {
            while (rs.next()) {
                out.add(new EventDeriver.ApprovalSrc(
                        rs.getString("identityrequestapprovalid"), rs.getString("request_source_id"),
                        rs.getString("owner_id"), rs.getString("owner"), rs.getString("state"),
                        (Boolean) rs.getObject("approved"), rs.getString("completer"), rs.getString("work_item_id"),
                        ts(rs, "start_date"), ts(rs, "end_date")));
            }
        }
        return out;
    }

    List<EventDeriver.TaskSrc> readTasks(Connection conn) throws SQLException {
        List<EventDeriver.TaskSrc> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(tasksSql(schema))) {
            while (rs.next()) {
                out.add(new EventDeriver.TaskSrc(
                        rs.getString("taskresultid"), rs.getString("name"), rs.getString("type"),
                        rs.getString("completion_status"), rs.getString("target_class"), rs.getString("target_id"),
                        ts(rs, "launched_at"), ts(rs, "completed_at")));
            }
        }
        return out;
    }

    List<EventDeriver.ProvSrc> readProvisioning(Connection conn) throws SQLException {
        List<EventDeriver.ProvSrc> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(provisioningSql(schema))) {
            while (rs.next()) {
                out.add(new EventDeriver.ProvSrc(
                        rs.getString("provisioningtxnid"), rs.getString("operation"), rs.getString("type"),
                        rs.getString("status"), rs.getString("plan_result_status"), rs.getString("identity_name"),
                        rs.getString("application_name"), rs.getString("access_request_id"), rs.getString("owner_id"),
                        rs.getString("wait_work_item_id"), rs.getString("manual_work_item_id"),
                        ts(rs, "created_at")));
            }
        }
        return out;
    }

    List<EventDeriver.ArchiveSrc> readArchives(Connection conn) throws SQLException {
        List<EventDeriver.ArchiveSrc> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(archivesSql(schema))) {
            while (rs.next()) {
                out.add(new EventDeriver.ArchiveSrc(
                        rs.getString("source_id"), rs.getString("name"), rs.getString("type"),
                        rs.getString("state"), rs.getString("completer"), (Boolean) rs.getObject("is_signed"),
                        rs.getString("identity_request_id"), rs.getString("work_item_id"),
                        ts(rs, "archived_ts")));
            }
        }
        return out;
    }

    List<EventDeriver.CertItemSrc> readCertItems(Connection conn) throws SQLException {
        List<EventDeriver.CertItemSrc> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(certItemsSql(schema))) {
            while (rs.next()) {
                out.add(new EventDeriver.CertItemSrc(
                        rs.getString("certificationitemid"), rs.getString("certification_id"),
                        rs.getString("entity_id"), rs.getString("identity"), rs.getString("action_status"),
                        rs.getString("action_remediation_action"), rs.getString("action_actor_name"),
                        (Boolean) rs.getObject("action_is_approved"), (Boolean) rs.getObject("action_is_remediation"),
                        (Boolean) rs.getObject("action_is_revoke_account"), (Boolean) rs.getObject("acted_upon"),
                        rs.getString("owner_id"), ts(rs, "action_decision_date"), ts(rs, "finished_date"),
                        ts(rs, "completed")));
            }
        }
        return out;
    }

    boolean tableExists(Connection conn, String unqualified) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name = ?)")) {
            ps.setString(1, schema);
            ps.setString(2, unqualified);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** timestamptz → the same instant (Part 1 converts to UTC LocalDateTime and writes it back at UTC). */
    private static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}

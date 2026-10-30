package com.keyforge.part2.event;

import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Derives the append-only {@code kf_event} store from the already-loaded Part 2 native tables — the event half of
 * Part 1's {@code iiq.nativeevent.NativeEventDerivationService} (same five sources, same columns, same existence guard).
 * It calls no KF Agent endpoint and never modifies a source table. A missing source table is reported in
 * {@link Result#sourcesMissing} and contributes no events (never fabricated).
 *
 * <p>Each source table is read in chunks of {@link #CHUNK} rows in primary-key order, never the whole table at once;
 * a chunk's events are appended and committed before the next chunk is read. A {@link Scope} narrows what is read: a
 * {@code start}/{@code limit} window of each source table (in primary-key order), and/or only the source rows Part 2
 * wrote since a given time ({@code extracted_at}: the incremental mode). Re-running is idempotent
 * ({@code ON CONFLICT (event_id) DO NOTHING}): existing events are never updated or deleted, so a failure part-way keeps
 * the chunks already committed and the next run completes the rest.
 */
public final class EventDerivationService {

    static final String APPROVAL = "kf_identity_request_approval";
    static final String TASK_RESULT = "kf_task_result";
    static final String PROVISIONING_TXN = "kf_provisioning_txn";
    static final String WORKITEM_ARCHIVE = "kf_workitem_archive";
    static final String CERTIFICATION_ITEM = "kf_certification_item";

    /** Source rows read per chunk. */
    public static final int CHUNK = 1000;

    /**
     * Which source rows a pass reads: a {@code start}/{@code limit} window of each source table in primary-key order
     * (null = from the first row / to the last), and/or only rows Part 2 wrote since {@code changedSince} (null = all).
     */
    public record Scope(Integer start, Integer limit, Instant changedSince) {
        public static final Scope ALL = new Scope(null, null, null);
    }

    /** One source table: its name, primary key and the columns the deriver reads. */
    record Source(String table, String pk, String columns) { }

    static final Source APPROVAL_SOURCE = new Source(APPROVAL, "identityrequestapprovalid",
            "identityrequestapprovalid, request_source_id, owner_id, owner, state, approved, completer, work_item_id, "
                    + "start_date, end_date");
    static final Source TASK_SOURCE = new Source(TASK_RESULT, "taskresultid",
            "taskresultid, name, type, completion_status, target_class, target_id, launched_at, completed_at");
    static final Source PROVISIONING_SOURCE = new Source(PROVISIONING_TXN, "provisioningtxnid",
            "provisioningtxnid, operation, type, status, plan_result_status, identity_name, application_name, "
                    + "access_request_id, owner_id, wait_work_item_id, manual_work_item_id, created_at");
    static final Source ARCHIVE_SOURCE = new Source(WORKITEM_ARCHIVE, "archiveid",
            "source_id, name, type, state, completer, is_signed, identity_request_id, work_item_id, archived_ts");
    static final Source CERT_ITEM_SOURCE = new Source(CERTIFICATION_ITEM, "certificationitemid",
            "certificationitemid, certification_id, entity_id, identity, action_status, action_remediation_action, "
                    + "action_actor_name, action_is_approved, action_is_remediation, action_is_revoke_account, "
                    + "acted_upon, owner_id, action_decision_date, finished_date, completed");

    private final String schema;
    private final EventRepository eventRepo;
    private final int chunk;

    public EventDerivationService(String schema) {
        this(schema, CHUNK);
    }

    EventDerivationService(String schema, int chunk) {
        this.schema = SchemaName.validate(schema);
        this.eventRepo = new EventRepository(this.schema);
        this.chunk = chunk > 0 ? chunk : CHUNK;
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

    /** One full derivation pass for {@code runId}. */
    public Result derive(Connection conn, String runId) throws SQLException {
        return derive(conn, runId, Scope.ALL);
    }

    /** One pass over {@code scope}: each chunk is committed; a failing chunk is rolled back and the error rethrown. */
    public Result derive(Connection conn, String runId, Scope scope) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            eventRepo.ensureTargetTable(conn);
            conn.commit();
            Result r = new Result();
            r.approvalRows = deriveSource(conn, APPROVAL_SOURCE, scope, r, EventDerivationService::approval,
                    batch -> EventDeriver.deriveAll(batch, List.of(), List.of(), List.of(), List.of(), runId));
            r.taskRows = deriveSource(conn, TASK_SOURCE, scope, r, EventDerivationService::task,
                    batch -> EventDeriver.deriveAll(List.of(), batch, List.of(), List.of(), List.of(), runId));
            r.provRows = deriveSource(conn, PROVISIONING_SOURCE, scope, r, EventDerivationService::provisioning,
                    batch -> EventDeriver.deriveAll(List.of(), List.of(), batch, List.of(), List.of(), runId));
            r.archiveRows = deriveSource(conn, ARCHIVE_SOURCE, scope, r, EventDerivationService::archive,
                    batch -> EventDeriver.deriveAll(List.of(), List.of(), List.of(), batch, List.of(), runId));
            r.certItemRows = deriveSource(conn, CERT_ITEM_SOURCE, scope, r, EventDerivationService::certItem,
                    batch -> EventDeriver.deriveAll(List.of(), List.of(), List.of(), List.of(), batch, runId));
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

    /** Derives and appends one source table chunk by chunk; returns the rows read (0 when the table is absent). */
    private <T> int deriveSource(Connection conn, Source source, Scope scope, Result r, RowMapper<T> mapper,
                                 Function<List<T>, List<EventRow>> derive) throws SQLException {
        if (!tableExists(conn, source.table())) {
            r.sourcesMissing.add(source.table());
            return 0;
        }
        return scan(conn, source, scope, mapper, batch -> {
            for (EventRow e : derive.apply(batch)) {
                r.eventsDerived++;
                r.eventsByType.merge(e.eventType(), 1, Integer::sum);
                if (eventRepo.append(conn, e)) {
                    r.eventsInserted++;
                    r.insertedByType.merge(e.eventType(), 1, Integer::sum);
                }
            }
            conn.commit();
        });
    }

    /** Maps the current result-set row. */
    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Handles one chunk of mapped rows. */
    @FunctionalInterface
    interface ChunkHandler<T> {
        void handle(List<T> batch) throws SQLException;
    }

    /**
     * Reads {@code source} within {@code scope} in chunks (primary-key order, keyset paging after the last key read),
     * handing each non-empty chunk to {@code handler}; returns the rows read.
     */
    <T> int scan(Connection conn, Source source, Scope scope, RowMapper<T> mapper, ChunkHandler<T> handler)
            throws SQLException {
        String sql = chunkSql(schema, source, scope, chunk);
        String lastKey = null;
        int total = 0;
        while (true) {
            List<T> batch = new ArrayList<>();
            String batchLast = null;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int i = 1;
                if (scope.changedSince() != null) {
                    ps.setObject(i++, OffsetDateTime.ofInstant(scope.changedSince(), ZoneOffset.UTC));
                }
                ps.setInt(i++, scope.start() == null ? 0 : scope.start());
                if (scope.limit() != null) {
                    ps.setInt(i++, scope.limit());
                }
                ps.setString(i++, lastKey);
                ps.setString(i, lastKey);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        batch.add(mapper.map(rs));
                        batchLast = rs.getString("kf_chunk_key");
                    }
                }
            }
            total += batch.size();
            if (!batch.isEmpty()) {
                handler.handle(batch);
            }
            if (batch.size() < chunk || batchLast == null) {
                return total;
            }
            lastKey = batchLast;
        }
    }

    /** One chunk of {@code source} within {@code scope}, after the last primary key read (none on the first chunk). */
    static String chunkSql(String schema, Source source, Scope scope, int chunk) {
        return "SELECT " + source.columns() + ", " + source.pk() + "::text AS kf_chunk_key FROM (SELECT * FROM "
                + schema + "." + source.table()
                + (scope.changedSince() != null ? " WHERE extracted_at >= ?" : "")
                + " ORDER BY " + source.pk() + " OFFSET ?" + (scope.limit() != null ? " LIMIT ?" : "") + ") w"
                + " WHERE (?::uuid IS NULL OR " + source.pk() + " > ?::uuid) ORDER BY " + source.pk()
                + " LIMIT " + chunk;
    }

    // --- the columns read from the native source tables (Part 1's statements) ---

    static String approvalsSql(String schema) {
        return "SELECT " + APPROVAL_SOURCE.columns() + " FROM " + schema + "." + APPROVAL;
    }

    static String tasksSql(String schema) {
        return "SELECT " + TASK_SOURCE.columns() + " FROM " + schema + "." + TASK_RESULT;
    }

    static String provisioningSql(String schema) {
        return "SELECT " + PROVISIONING_SOURCE.columns() + " FROM " + schema + "." + PROVISIONING_TXN;
    }

    static String archivesSql(String schema) {
        return "SELECT " + ARCHIVE_SOURCE.columns() + " FROM " + schema + "." + WORKITEM_ARCHIVE;
    }

    static String certItemsSql(String schema) {
        return "SELECT " + CERT_ITEM_SOURCE.columns() + " FROM " + schema + "." + CERTIFICATION_ITEM;
    }

    static EventDeriver.ApprovalSrc approval(ResultSet rs) throws SQLException {
        return new EventDeriver.ApprovalSrc(
                rs.getString("identityrequestapprovalid"), rs.getString("request_source_id"),
                rs.getString("owner_id"), rs.getString("owner"), rs.getString("state"),
                (Boolean) rs.getObject("approved"), rs.getString("completer"), rs.getString("work_item_id"),
                ts(rs, "start_date"), ts(rs, "end_date"));
    }

    static EventDeriver.TaskSrc task(ResultSet rs) throws SQLException {
        return new EventDeriver.TaskSrc(
                rs.getString("taskresultid"), rs.getString("name"), rs.getString("type"),
                rs.getString("completion_status"), rs.getString("target_class"), rs.getString("target_id"),
                ts(rs, "launched_at"), ts(rs, "completed_at"));
    }

    static EventDeriver.ProvSrc provisioning(ResultSet rs) throws SQLException {
        return new EventDeriver.ProvSrc(
                rs.getString("provisioningtxnid"), rs.getString("operation"), rs.getString("type"),
                rs.getString("status"), rs.getString("plan_result_status"), rs.getString("identity_name"),
                rs.getString("application_name"), rs.getString("access_request_id"), rs.getString("owner_id"),
                rs.getString("wait_work_item_id"), rs.getString("manual_work_item_id"),
                ts(rs, "created_at"));
    }

    static EventDeriver.ArchiveSrc archive(ResultSet rs) throws SQLException {
        return new EventDeriver.ArchiveSrc(
                rs.getString("source_id"), rs.getString("name"), rs.getString("type"),
                rs.getString("state"), rs.getString("completer"), (Boolean) rs.getObject("is_signed"),
                rs.getString("identity_request_id"), rs.getString("work_item_id"),
                ts(rs, "archived_ts"));
    }

    static EventDeriver.CertItemSrc certItem(ResultSet rs) throws SQLException {
        return new EventDeriver.CertItemSrc(
                rs.getString("certificationitemid"), rs.getString("certification_id"),
                rs.getString("entity_id"), rs.getString("identity"), rs.getString("action_status"),
                rs.getString("action_remediation_action"), rs.getString("action_actor_name"),
                (Boolean) rs.getObject("action_is_approved"), (Boolean) rs.getObject("action_is_remediation"),
                (Boolean) rs.getObject("action_is_revoke_account"), (Boolean) rs.getObject("acted_upon"),
                rs.getString("owner_id"), ts(rs, "action_decision_date"), ts(rs, "finished_date"),
                ts(rs, "completed"));
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

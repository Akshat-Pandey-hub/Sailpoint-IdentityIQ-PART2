package com.keyforge.part2.event;

import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Derives the append-only native {@code kf_event_link} store for events that already exist in {@code kf_event}.
 * It reads the same five loaded native tables with the same readers as {@link EventDerivationService}, derives each
 * row's event with the unchanged {@link EventDeriver} (to obtain its verified {@code event_id}) and that event's
 * explicit links with {@link EventLinkDeriver}. {@code kf_event} is only read, never written: a link whose event is
 * not stored yet (the source changed, or derive-native-events has not run) is counted and not written, so every
 * written link references a stored event.
 *
 * <p>Preconditions are checked before any write: a source table that exists must have every column the readers use,
 * and an existing {@code kf_event_link} must be the native shape. Each source table is then read in chunks (primary-key
 * order, within the same {@link EventDerivationService.Scope} as event derivation); a chunk's links are checked against
 * only that chunk's event ids in {@code kf_event}, appended and committed before the next chunk is read. Re-running is
 * idempotent ({@code ON CONFLICT (link_id) DO NOTHING}); stored links are never updated or deleted.
 */
public final class EventLinkDerivationService {

    /** The columns the readers select from each source table (a present table must have all of them). */
    static final Map<String, List<String>> REQUIRED_COLUMNS = new LinkedHashMap<>();

    static {
        REQUIRED_COLUMNS.put(EventDerivationService.APPROVAL, List.of("identityrequestapprovalid",
                "request_source_id", "owner_id", "owner", "state", "approved", "completer", "work_item_id",
                "start_date", "end_date"));
        REQUIRED_COLUMNS.put(EventDerivationService.TASK_RESULT, List.of("taskresultid", "name", "type",
                "completion_status", "target_class", "target_id", "launched_at", "completed_at"));
        REQUIRED_COLUMNS.put(EventDerivationService.PROVISIONING_TXN, List.of("provisioningtxnid", "operation",
                "type", "status", "plan_result_status", "identity_name", "application_name", "access_request_id",
                "owner_id", "wait_work_item_id", "manual_work_item_id", "created_at"));
        REQUIRED_COLUMNS.put(EventDerivationService.WORKITEM_ARCHIVE, List.of("source_id", "name", "type", "state",
                "completer", "is_signed", "identity_request_id", "work_item_id", "archived_ts"));
        REQUIRED_COLUMNS.put(EventDerivationService.CERTIFICATION_ITEM, List.of("certificationitemid",
                "certification_id", "entity_id", "identity", "action_status", "action_remediation_action",
                "action_actor_name", "action_is_approved", "action_is_remediation", "action_is_revoke_account",
                "acted_upon", "owner_id", "action_decision_date", "finished_date", "completed"));
    }

    private final String schema;
    private final EventDerivationService sources;
    private final EventLinkRepository linkRepo;

    public EventLinkDerivationService(String schema) {
        this(schema, EventDerivationService.CHUNK);
    }

    EventLinkDerivationService(String schema, int chunk) {
        this.schema = SchemaName.validate(schema);
        this.sources = new EventDerivationService(this.schema, chunk);
        this.linkRepo = new EventLinkRepository(this.schema);
    }

    public String linkTable() {
        return linkRepo.table();
    }

    /** Aggregate outcome (all counts exact, measured in this pass). */
    public static final class Result {
        public int approvalRows;
        public int taskRows;
        public int provRows;
        public int archiveRows;
        public int certItemRows;
        public final List<String> sourcesMissing = new ArrayList<>();
        public boolean eventTableMissing;
        /** Links derived from the current source rows (before the event-existence filter). */
        public int linksDerived;
        /** Derived links not written because their event_id is not in kf_event. */
        public int linksWithoutEvent;
        public int linksInserted;
        /** Links not written because their link_id was already stored. */
        public int linksAlreadyPresent;
        public final Map<String, Integer> derivedByType = new LinkedHashMap<>();
        public final Map<String, Integer> insertedByType = new LinkedHashMap<>();
    }

    /** One full link pass for {@code runId}. */
    public Result derive(Connection conn, String runId) throws SQLException {
        return derive(conn, runId, EventDerivationService.Scope.ALL);
    }

    /** One link pass over {@code scope}: each chunk is committed; a failing chunk is rolled back and rethrown. */
    public Result derive(Connection conn, String runId, EventDerivationService.Scope scope) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Result r = deriveChunks(conn, runId, scope);
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

    private Result deriveChunks(Connection conn, String runId, EventDerivationService.Scope scope) throws SQLException {
        Result r = new Result();

        // --- preconditions, before any write ---
        Map<String, Boolean> present = new LinkedHashMap<>();
        List<String> missingColumns = new ArrayList<>();
        for (Map.Entry<String, List<String>> t : REQUIRED_COLUMNS.entrySet()) {
            boolean exists = sources.tableExists(conn, t.getKey());
            present.put(t.getKey(), exists);
            if (!exists) {
                r.sourcesMissing.add(t.getKey());
                continue;
            }
            Set<String> have = columnsOf(conn, t.getKey());
            for (String c : t.getValue()) {
                if (!have.contains(c)) {
                    missingColumns.add(t.getKey() + "." + c);
                }
            }
        }
        if (!missingColumns.isEmpty()) {
            throw new MissingSourceColumnsException("source table(s) lack required column(s) " + missingColumns
                    + "; nothing was written");
        }
        linkRepo.checkCompatible(conn);
        r.eventTableMissing = !sources.tableExists(conn, EventRepository.TABLE);
        linkRepo.ensureTargetTable(conn);
        conn.commit();

        // --- each source, chunk by chunk ---
        if (present.get(EventDerivationService.APPROVAL)) {
            r.approvalRows = linkSource(conn, EventDerivationService.APPROVAL_SOURCE, scope, r,
                    EventDerivationService::approval,
                    b -> EventLinkDeriver.deriveAll(b, List.of(), List.of(), List.of(), List.of(), runId));
        }
        if (present.get(EventDerivationService.TASK_RESULT)) {
            r.taskRows = linkSource(conn, EventDerivationService.TASK_SOURCE, scope, r, EventDerivationService::task,
                    b -> EventLinkDeriver.deriveAll(List.of(), b, List.of(), List.of(), List.of(), runId));
        }
        if (present.get(EventDerivationService.PROVISIONING_TXN)) {
            r.provRows = linkSource(conn, EventDerivationService.PROVISIONING_SOURCE, scope, r,
                    EventDerivationService::provisioning,
                    b -> EventLinkDeriver.deriveAll(List.of(), List.of(), b, List.of(), List.of(), runId));
        }
        if (present.get(EventDerivationService.WORKITEM_ARCHIVE)) {
            r.archiveRows = linkSource(conn, EventDerivationService.ARCHIVE_SOURCE, scope, r,
                    EventDerivationService::archive,
                    b -> EventLinkDeriver.deriveAll(List.of(), List.of(), List.of(), b, List.of(), runId));
        }
        if (present.get(EventDerivationService.CERTIFICATION_ITEM)) {
            r.certItemRows = linkSource(conn, EventDerivationService.CERT_ITEM_SOURCE, scope, r,
                    EventDerivationService::certItem,
                    b -> EventLinkDeriver.deriveAll(List.of(), List.of(), List.of(), List.of(), b, runId));
        }
        return r;
    }

    /** Derives, checks and appends one source table's links chunk by chunk; returns the source rows read. */
    private <T> int linkSource(Connection conn, EventDerivationService.Source source, EventDerivationService.Scope scope,
                               Result r, EventDerivationService.RowMapper<T> mapper,
                               Function<List<T>, List<EventLinkRow>> derive) throws SQLException {
        return sources.scan(conn, source, scope, mapper, batch -> {
            List<EventLinkRow> links = derive.apply(batch);
            Set<String> eventIds = new LinkedHashSet<>();
            links.forEach(l -> eventIds.add(l.eventId()));
            Set<String> stored = r.eventTableMissing ? Set.of() : linkRepo.existingEventIds(conn, eventIds);
            for (EventLinkRow l : links) {
                r.linksDerived++;
                r.derivedByType.merge(l.linkType(), 1, Integer::sum);
                if (!stored.contains(l.eventId())) {
                    r.linksWithoutEvent++;
                    continue;
                }
                if (linkRepo.append(conn, l)) {
                    r.linksInserted++;
                    r.insertedByType.merge(l.linkType(), 1, Integer::sum);
                } else {
                    r.linksAlreadyPresent++;
                }
            }
            conn.commit();
        });
    }

    private Set<String> columnsOf(Connection conn, String table) throws SQLException {
        Set<String> cols = new java.util.HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
        }
        return cols;
    }

    /** A present source table lacks a column the readers need: nothing is written. */
    public static final class MissingSourceColumnsException extends SQLException {
        public MissingSourceColumnsException(String message) {
            super(message);
        }
    }
}

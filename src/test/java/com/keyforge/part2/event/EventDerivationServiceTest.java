package com.keyforge.part2.event;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventDerivationService} over a fake connection that holds the native source rows and an in-memory
 * {@code kf_event} keyed by event_id ({@code ON CONFLICT DO NOTHING} semantics, committed only on commit).
 */
class EventDerivationServiceTest {

    private static final String S = "iiq_native_part2";
    private static final OffsetDateTime TS = OffsetDateTime.of(2026, 6, 27, 8, 0, 24, 753_000_000,
            ZoneOffset.ofHoursMinutes(5, 30));

    /** In-memory fake of the schema. */
    private static final class Db {
        final Map<String, List<Map<String, Object>>> sources = new LinkedHashMap<>();
        final Set<String> committed = new HashSet<>();
        final Set<String> pending = new HashSet<>();
        final List<Map<Integer, Object>> inserts = new ArrayList<>();
        final List<String> sql = new ArrayList<>();
        int commits;
        int rollbacks;
        boolean autoCommit = true;
        int failOnInsertNumber = -1;
        int insertAttempts;

        Db table(String name, Map<String, Object> row) {
            sources.computeIfAbsent(name, k -> new ArrayList<>()).add(row);
            return this;
        }

        Db emptyTable(String name) {
            sources.computeIfAbsent(name, k -> new ArrayList<>());
            return this;
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "createStatement" -> statement();
                        case "prepareStatement" -> prepared((String) a[0]);
                        case "getAutoCommit" -> autoCommit;
                        case "setAutoCommit" -> {
                            autoCommit = (Boolean) a[0];
                            yield null;
                        }
                        case "commit" -> {
                            commits++;
                            committed.addAll(pending);
                            pending.clear();
                            yield null;
                        }
                        case "rollback" -> {
                            rollbacks++;
                            pending.clear();
                            yield null;
                        }
                        default -> defaultFor(m);
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                    (p, m, a) -> {
                        if (m.getName().equals("execute")) {
                            sql.add((String) a[0]);
                            return false;
                        }
                        if (m.getName().equals("executeQuery")) {
                            String q = (String) a[0];
                            sql.add(q);
                            String table = q.substring(q.lastIndexOf(S + ".") + S.length() + 1).trim();
                            List<Map<String, Object>> rows = sources.get(table);
                            if (rows == null) throw new SQLException("relation " + table + " does not exist");
                            return sourceRows(rows);
                        }
                        return defaultFor(m);
                    });
        }

        private PreparedStatement prepared(String q) {
            sql.add(q);
            Map<Integer, Object> bound = new LinkedHashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "setString", "setObject" -> bound.put((Integer) a[0], a[1]);
                            case "setNull" -> bound.put((Integer) a[0], null);
                            case "executeQuery" -> {
                                if (q.startsWith("SELECT EXISTS")) {
                                    return bool(sources.containsKey((String) bound.get(2)));
                                }
                                if (++insertAttempts == failOnInsertNumber) {
                                    throw new SQLException("simulated insert failure");
                                }
                                inserts.add(new LinkedHashMap<>(bound));
                                String id = (String) bound.get(1);
                                boolean isNew = !committed.contains(id) && pending.add(id);
                                return bool(isNew ? Boolean.TRUE : null);
                            }
                            default -> { }
                        }
                        return defaultFor(m);
                    });
        }

        /** {@code bool(null)} = no row (ON CONFLICT DO NOTHING returned nothing). */
        private ResultSet bool(Boolean value) {
            boolean[] done = {false};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "next" -> {
                            boolean has = !done[0] && value != null;
                            done[0] = true;
                            yield has;
                        }
                        case "getBoolean" -> value;
                        default -> defaultFor(m);
                    });
        }

        private ResultSet sourceRows(List<Map<String, Object>> rows) {
            int[] pos = {-1};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "next" -> ++pos[0] < rows.size();
                        case "getString" -> (String) rows.get(pos[0]).get((String) a[0]);
                        case "getObject" -> rows.get(pos[0]).get((String) a[0]);
                        default -> defaultFor(m);
                    });
        }
    }

    private static Object defaultFor(Method method) {
        Class<?> r = method.getReturnType();
        if (r == boolean.class) return Boolean.FALSE;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        return null;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Db fullDb() {
        return new Db()
                .table("kf_identity_request_approval", row("identityrequestapprovalid", "ap1",
                        "request_source_id", "req1", "owner", "spadmin", "state", "Finished", "approved", true,
                        "work_item_id", "wi1", "start_date", TS, "end_date", TS))
                .table("kf_task_result", row("taskresultid", "t1", "name", "Refresh", "type", "System",
                        "completion_status", "Success", "launched_at", TS, "completed_at", TS))
                .table("kf_task_result", row("taskresultid", "t2", "name", "Leaver", "type", "Workflow",
                        "launched_at", TS))
                .table("kf_provisioning_txn", row("provisioningtxnid", "px1", "operation", "Create", "type", "Auto",
                        "status", "Success", "created_at", TS))
                .table("kf_workitem_archive", row("source_id", "arc1", "name", "Approve", "type", "Approval",
                        "is_signed", false, "archived_ts", TS))
                .table("kf_certification_item", row("certificationitemid", "ci1", "certification_id", "c1",
                        "action_status", "Approved", "acted_upon", true, "action_decision_date", TS))
                .table("kf_certification_item", row("certificationitemid", "ci2", "certification_id", "c1",
                        "acted_upon", false));
    }

    @Test
    void derivesFromEverySourceInOneCommittedTransaction() throws Exception {
        Db db = fullDb();
        EventDerivationService.Result r = new EventDerivationService(S).derive(db.connection(), "run-1");

        assertEquals(1, r.approvalRows);
        assertEquals(2, r.taskRows);
        assertEquals(1, r.provRows);
        assertEquals(1, r.archiveRows);
        assertEquals(2, r.certItemRows);
        assertTrue(r.sourcesMissing.isEmpty());
        assertEquals(6, r.eventsDerived, "the undecided cert item yields nothing");
        assertEquals(6, r.eventsInserted);
        assertEquals(0, r.eventsAlreadyPresent());
        assertEquals(Map.of("APPROVAL_DECISION", 1, "TASK_COMPLETED", 1, "TASK_LAUNCHED", 1, "PROVISIONING_TXN", 1,
                "WORKITEM_ARCHIVED", 1, "CERTIFICATION_DECISION", 1), r.eventsByType);
        assertEquals(1, db.commits);
        assertEquals(0, db.rollbacks);
        assertTrue(db.autoCommit, "auto-commit restored");
        assertEquals(6, db.committed.size());
    }

    @Test
    void timestampsKeepTheirInstantAndRowsCarryTheRunId() throws Exception {
        Db db = fullDb();
        new EventDerivationService(S).derive(db.connection(), "run-1");
        Map<Integer, Object> first = db.inserts.get(0);
        assertEquals(OffsetDateTime.parse("2026-06-27T02:30:24.753Z"), first.get(7), "bound at UTC, same instant");
        assertEquals("precise", first.get(8));
        assertEquals("run-1", first.get(9));
        assertEquals("native_iiq_java_api", first.get(10));
    }

    @Test
    void rerunIsIdempotentAndAppendOnly() throws Exception {
        Db db = fullDb();
        EventDerivationService svc = new EventDerivationService(S);
        svc.derive(db.connection(), "run-1");
        EventDerivationService.Result again = svc.derive(db.connection(), "run-2");

        assertEquals(6, again.eventsDerived);
        assertEquals(0, again.eventsInserted);
        assertEquals(6, again.eventsAlreadyPresent());
        assertTrue(again.insertedByType.isEmpty());
        assertEquals(6, db.committed.size());
        for (String s : db.sql) {
            String u = s.toUpperCase();
            assertFalse(u.startsWith("UPDATE") || u.startsWith("DELETE") || u.contains("DO UPDATE")
                    || u.startsWith("TRUNCATE"), s);
        }
    }

    @Test
    void aChangedSourceFactAddsANewEventAndKeepsTheOldOne() throws Exception {
        Db db = fullDb();
        EventDerivationService svc = new EventDerivationService(S);
        svc.derive(db.connection(), "run-1");
        db.sources.get("kf_provisioning_txn").get(0).put("status", "Failed");
        EventDerivationService.Result r = svc.derive(db.connection(), "run-2");
        assertEquals(1, r.eventsInserted);
        assertEquals(Map.of("PROVISIONING_TXN", 1), r.insertedByType);
        assertEquals(7, db.committed.size());
    }

    @Test
    void missingSourceTablesAreReportedNotFabricated() throws Exception {
        Db db = new Db().emptyTable("kf_task_result")
                .table("kf_provisioning_txn", row("provisioningtxnid", "px1", "created_at", TS));
        EventDerivationService.Result r = new EventDerivationService(S).derive(db.connection(), "run-1");
        assertEquals(List.of("kf_identity_request_approval", "kf_workitem_archive", "kf_certification_item"),
                r.sourcesMissing);
        assertEquals(1, r.eventsDerived);
        assertEquals(0, r.taskRows);
        assertFalse(db.sql.stream().anyMatch(s -> s.contains("FROM " + S + ".kf_identity_request_approval")),
                "an absent table is never queried");
    }

    @Test
    void aFailureRollsBackTheWholePass() {
        Db db = fullDb();
        db.failOnInsertNumber = 4;
        SQLException e = assertThrows(SQLException.class,
                () -> new EventDerivationService(S).derive(db.connection(), "run-1"));
        assertEquals("simulated insert failure", e.getMessage());
        assertEquals(0, db.commits);
        assertEquals(1, db.rollbacks);
        assertTrue(db.committed.isEmpty(), "kf_event unchanged");
        assertTrue(db.autoCommit);
    }

    @Test
    void sourceSqlIsPart1sReadOnlySelects() {
        assertEquals("SELECT taskresultid, name, type, completion_status, target_class, target_id, "
                + "launched_at, completed_at FROM iiq_native_part2.kf_task_result", EventDerivationService.tasksSql(S));
        assertTrue(EventDerivationService.archivesSql(S).startsWith("SELECT source_id, name, type, state"));
        assertTrue(EventDerivationService.approvalsSql(S).endsWith("FROM iiq_native_part2.kf_identity_request_approval"));
        assertTrue(EventDerivationService.provisioningSql(S).endsWith("FROM iiq_native_part2.kf_provisioning_txn"));
        assertTrue(EventDerivationService.certItemsSql(S).endsWith("FROM iiq_native_part2.kf_certification_item"));
    }

    @Test
    void repositoryDdlAndInsertMatchPart1() {
        EventRepository repo = new EventRepository(S);
        assertEquals("CREATE TABLE IF NOT EXISTS iiq_native_part2.kf_event (event_id uuid PRIMARY KEY, "
                + "src_system text, src_object_type text, src_object_id text, event_type text, event_fingerprint text, "
                + "src_event_ts timestamptz, src_event_ts_precision text, extraction_run_id text, src_interface text, "
                + "raw_ref text, event_detail jsonb, extracted_at timestamptz NOT NULL DEFAULT now())",
                repo.createTableSql());
        String insert = repo.appendSql();
        assertTrue(insert.contains("ON CONFLICT (event_id) DO NOTHING"), insert);
        assertTrue(insert.contains("NULL, ?::jsonb"), "raw_ref is NULL: " + insert);
        assertFalse(insert.contains("DO UPDATE"), insert);
    }
}

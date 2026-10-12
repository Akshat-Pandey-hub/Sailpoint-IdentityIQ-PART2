package com.keyforge.part2.event;

import com.keyforge.part2.ledger.ExtractionRun;
import com.keyforge.part2.ledger.RunContext;
import com.keyforge.part2.ledger.RunLedger;
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
 * {@link EventLinkDerivationService} over a fake connection holding the native source rows, a stored {@code kf_event}
 * id set and an in-memory {@code kf_event_link} keyed by link_id ({@code ON CONFLICT DO NOTHING}, committed only on
 * commit).
 */
class EventLinkDerivationServiceTest {

    private static final String S = "iiq_native_part2";
    private static final OffsetDateTime TS = OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    private static final class Db {
        final Map<String, List<Map<String, Object>>> sources = new LinkedHashMap<>();
        final Map<String, Set<String>> droppedColumns = new HashMap<>();
        /** Existing kf_event_link columns → "NOT NULL without default" (empty = table absent). */
        final Map<String, Boolean> linkTableColumns = new LinkedHashMap<>();
        Set<String> storedEvents = new HashSet<>();
        boolean eventTable = true;
        final Map<String, Map<Integer, Object>> committedLinks = new LinkedHashMap<>();
        final Map<String, Map<Integer, Object>> pendingLinks = new LinkedHashMap<>();
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
                            committedLinks.putAll(pendingLinks);
                            pendingLinks.clear();
                            yield null;
                        }
                        case "rollback" -> {
                            rollbacks++;
                            pendingLinks.clear();
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
                            if (q.startsWith("SELECT event_id::text FROM " + S + ".kf_event")) {
                                List<Object[]> ids = new ArrayList<>();
                                storedEvents.forEach(id -> ids.add(new Object[]{id}));
                                return indexed(ids);
                            }
                            String table = q.substring(q.lastIndexOf(S + ".") + S.length() + 1).trim();
                            List<Map<String, Object>> rows = sources.get(table);
                            if (rows == null) throw new SQLException("relation " + table + " does not exist");
                            return named(rows);
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
                            case "executeQuery" -> {
                                String table = (String) bound.get(2);
                                if (q.startsWith("SELECT EXISTS")) {
                                    boolean exists = table.equals("kf_event") ? eventTable : sources.containsKey(table);
                                    return indexed(List.<Object[]>of(new Object[]{exists}));
                                }
                                if (q.startsWith("SELECT column_name, (is_nullable")) {
                                    List<Object[]> cols = new ArrayList<>();
                                    linkTableColumns.forEach((c, req) -> cols.add(new Object[]{c, req}));
                                    return indexed(cols);
                                }
                                if (q.startsWith("SELECT column_name FROM")) {
                                    List<Object[]> cols = new ArrayList<>();
                                    for (String c : EventLinkDerivationService.REQUIRED_COLUMNS.get(table)) {
                                        if (!droppedColumns.getOrDefault(table, Set.of()).contains(c)) {
                                            cols.add(new Object[]{c});
                                        }
                                    }
                                    return indexed(cols);
                                }
                                if (++insertAttempts == failOnInsertNumber) {
                                    throw new SQLException("simulated insert failure");
                                }
                                String id = (String) bound.get(1);
                                boolean isNew = !committedLinks.containsKey(id) && !pendingLinks.containsKey(id);
                                if (isNew) pendingLinks.put(id, new LinkedHashMap<>(bound));
                                return indexed(isNew ? List.<Object[]>of(new Object[]{id}) : List.of());
                            }
                            default -> { }
                        }
                        return defaultFor(m);
                    });
        }

        private ResultSet indexed(List<Object[]> rows) {
            int[] pos = {-1};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "next" -> ++pos[0] < rows.size();
                        case "getString" -> (String) rows.get(pos[0])[(Integer) a[0] - 1];
                        case "getBoolean" -> (Boolean) rows.get(pos[0])[(Integer) a[0] - 1];
                        default -> defaultFor(m);
                    });
        }

        private ResultSet named(List<Map<String, Object>> rows) {
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

    /** One row per source; 2 + 1 + 2 + 2 + 3 = 10 links; all their events stored. */
    private static Db fullDb() {
        Db db = new Db()
                .table("kf_identity_request_approval", row("identityrequestapprovalid", "ap1",
                        "request_source_id", "req1", "work_item_id", "wi1", "start_date", TS))
                .table("kf_task_result", row("taskresultid", "t1", "name", "Refresh",
                        "target_class", "sailpoint.object.Identity", "target_id", "id1", "launched_at", TS))
                .table("kf_provisioning_txn", row("provisioningtxnid", "px1", "access_request_id", "req1",
                        "owner_id", "own1", "wait_work_item_id", " ", "created_at", TS))
                .table("kf_workitem_archive", row("source_id", "arc1", "identity_request_id", "req1",
                        "work_item_id", "wi1", "archived_ts", TS))
                .table("kf_certification_item", row("certificationitemid", "ci1", "certification_id", "c1",
                        "entity_id", "e1", "owner_id", "own1", "acted_upon", true, "action_decision_date", TS))
                .table("kf_certification_item", row("certificationitemid", "ci2", "certification_id", "c1",
                        "entity_id", "e2", "acted_upon", false));
        db.storedEvents = allEventIds(db);
        return db;
    }

    /**
     * The event ids Step 4's own {@link EventDerivationService} stores for {@code db}'s current source rows (run on a
     * separate fake whose insert map, keyed by bound parameter #1, then holds the event_ids).
     */
    private static Set<String> allEventIds(Db db) {
        Db probe = new Db();
        probe.sources.putAll(db.sources);
        try {
            new EventDerivationService(S).derive(probe.connection(), "step-4");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return new HashSet<>(probe.committedLinks.keySet());
    }

    @Test
    void writesEveryExplicitLinkOfStoredEventsInOneTransaction() throws Exception {
        Db db = fullDb();
        EventLinkDerivationService.Result r = new EventLinkDerivationService(S).derive(db.connection(), "run-1");

        assertTrue(r.sourcesMissing.isEmpty());
        assertFalse(r.eventTableMissing);
        assertEquals(10, r.linksDerived, "blank wait id and undecided cert item yield nothing");
        assertEquals(10, r.linksInserted);
        assertEquals(0, r.linksAlreadyPresent);
        assertEquals(0, r.linksWithoutEvent);
        assertEquals(Map.of("APPROVAL_OF_REQUEST", 1, "APPROVAL_WORKITEM", 1, "TASK_TARGET", 1,
                "PROVISION_OF_REQUEST", 1, "PROVISION_OWNER", 1, "WORKITEM_OF_REQUEST", 1, "ARCHIVE_OF_WORKITEM", 1,
                "CERT_OF_CERTIFICATION", 1, "CERT_ITEM_ENTITY", 1, "CERT_OWNER", 1), r.derivedByType);
        assertEquals(1, db.commits);
        assertEquals(0, db.rollbacks);
        assertTrue(db.autoCommit);
        assertEquals(10, db.committedLinks.size());
        for (Map<Integer, Object> l : db.committedLinks.values()) {
            assertTrue(db.storedEvents.contains((String) l.get(2)), "every link references a stored event");
            assertEquals("EXPLICIT", l.get(8));
            assertEquals("run-1", l.get(9));
        }
    }

    @Test
    void neverWritesKfEvent() throws Exception {
        Db db = fullDb();
        new EventLinkDerivationService(S).derive(db.connection(), "run-1");
        for (String s : db.sql) {
            assertFalse(s.contains(S + ".kf_event ") && !s.startsWith("SELECT"), s);
            assertFalse(s.startsWith("INSERT INTO " + S + ".kf_event "), s);
            String u = s.toUpperCase();
            assertFalse(u.startsWith("UPDATE") || u.startsWith("DELETE") || u.startsWith("TRUNCATE")
                    || u.startsWith("DROP") || u.startsWith("ALTER") || u.contains("DO UPDATE"), s);
        }
    }

    @Test
    void rerunIsIdempotentAndStoredLinksStayUnchanged() throws Exception {
        Db db = fullDb();
        EventLinkDerivationService svc = new EventLinkDerivationService(S);
        svc.derive(db.connection(), "run-1");
        Map<String, Map<Integer, Object>> before = new LinkedHashMap<>(db.committedLinks);
        EventLinkDerivationService.Result again = svc.derive(db.connection(), "run-2");

        assertEquals(10, again.linksDerived);
        assertEquals(0, again.linksInserted);
        assertEquals(10, again.linksAlreadyPresent);
        assertEquals(before, db.committedLinks, "first run's rows (incl. extraction_run_id) untouched");
    }

    @Test
    void duplicateSourceFactsYieldOneLink() throws Exception {
        Db db = fullDb();
        db.table("kf_workitem_archive", row("source_id", "arc1", "identity_request_id", "req1",
                "work_item_id", "wi1", "archived_ts", TS));
        EventLinkDerivationService.Result r = new EventLinkDerivationService(S).derive(db.connection(), "run-1");
        assertEquals(12, r.linksDerived);
        assertEquals(10, r.linksInserted);
        assertEquals(2, r.linksAlreadyPresent, "same event + target → same link_id");
    }

    @Test
    void linksOfEventsNotInKfEventAreNotWritten() throws Exception {
        Db db = fullDb();
        db.sources.get("kf_provisioning_txn").get(0).put("status", "Failed"); // new fingerprint, event not stored
        EventLinkDerivationService.Result r = new EventLinkDerivationService(S).derive(db.connection(), "run-1");
        assertEquals(10, r.linksDerived);
        assertEquals(2, r.linksWithoutEvent);
        assertEquals(8, r.linksInserted);

        Db noEvents = fullDb();
        noEvents.eventTable = false;
        EventLinkDerivationService.Result none = new EventLinkDerivationService(S).derive(noEvents.connection(), "r");
        assertTrue(none.eventTableMissing);
        assertEquals(10, none.linksWithoutEvent);
        assertTrue(noEvents.committedLinks.isEmpty());
    }

    @Test
    void missingSourceTablesAreReportedNotFabricated() throws Exception {
        Db db = fullDb();
        db.sources.remove("kf_task_result");
        db.sources.remove("kf_certification_item");
        EventLinkDerivationService.Result r = new EventLinkDerivationService(S).derive(db.connection(), "run-1");
        assertEquals(List.of("kf_task_result", "kf_certification_item"), r.sourcesMissing);
        assertEquals(6, r.linksDerived);
        assertFalse(db.sql.stream().anyMatch(s -> s.contains("FROM " + S + ".kf_task_result")));
    }

    @Test
    void aMissingRequiredColumnFailsBeforeAnyWrite() {
        Db db = fullDb();
        db.droppedColumns.put("kf_provisioning_txn", Set.of("wait_work_item_id"));
        SQLException e = assertThrows(EventLinkDerivationService.MissingSourceColumnsException.class,
                () -> new EventLinkDerivationService(S).derive(db.connection(), "run-1"));
        assertTrue(e.getMessage().contains("kf_provisioning_txn.wait_work_item_id"), e.getMessage());
        assertTrue(db.committedLinks.isEmpty());
        assertFalse(db.sql.stream().anyMatch(s -> s.startsWith("CREATE")), "not even the table is created");
        assertEquals(1, db.rollbacks);
    }

    @Test
    void aLegacyShapedLinkTableIsRefusedAndLeftAlone() {
        Db db = fullDb();
        for (String c : List.of("id", "audit_event_id", "source_object_type", "target_object_type",
                "target_object_id", "target_raw", "target_type_hint", "link_status", "resolution_rule",
                "extracted_at")) {
            db.linkTableColumns.put(c, Set.of("id", "audit_event_id", "target_raw", "link_status").contains(c));
        }
        SQLException e = assertThrows(EventLinkRepository.IncompatibleLinkTableException.class,
                () -> new EventLinkDerivationService(S).derive(db.connection(), "run-1"));
        assertTrue(e.getMessage().contains("link_id"), e.getMessage());
        assertTrue(e.getMessage().contains("audit_event_id"), e.getMessage());
        assertTrue(db.committedLinks.isEmpty());
        assertFalse(db.sql.stream().anyMatch(s -> s.startsWith("CREATE") || s.startsWith("INSERT")
                || s.toUpperCase().startsWith("ALTER") || s.toUpperCase().startsWith("DROP")));
    }

    @Test
    void anExistingNativeLinkTableIsAccepted() throws Exception {
        Db db = fullDb();
        EventLinkRepository.NATIVE_COLUMNS.forEach(c -> db.linkTableColumns.put(c, c.equals("link_id")));
        assertEquals(10, new EventLinkDerivationService(S).derive(db.connection(), "run-1").linksInserted);
    }

    @Test
    void aFailureRollsBackTheWholePass() {
        Db db = fullDb();
        db.failOnInsertNumber = 5;
        SQLException e = assertThrows(SQLException.class,
                () -> new EventLinkDerivationService(S).derive(db.connection(), "run-1"));
        assertEquals("simulated insert failure", e.getMessage());
        assertEquals(0, db.commits);
        assertEquals(1, db.rollbacks);
        assertTrue(db.committedLinks.isEmpty(), "kf_event_link unchanged");
        assertTrue(db.autoCommit);
    }

    @Test
    void repositoryDdlIsTheNativeShapeWithoutAuditEventId() {
        EventLinkRepository repo = new EventLinkRepository(S);
        assertEquals("CREATE TABLE IF NOT EXISTS iiq_native_part2.kf_event_link (link_id uuid PRIMARY KEY, "
                + "event_id uuid, src_object_type text, src_object_id text, target_object_type text, "
                + "target_object_id text, link_type text, link_status text, extraction_run_id text, "
                + "extracted_at timestamptz NOT NULL DEFAULT now())", repo.createTableSql());
        assertFalse(repo.createTableSql().contains("audit_event_id"));
        assertTrue(repo.appendSql().contains("ON CONFLICT (link_id) DO NOTHING"), repo.appendSql());
        assertFalse(repo.appendSql().contains("DO UPDATE"));
        assertTrue(EventLinkRepository.incompatibility(Map.of()) == null, "absent table is fine");
        assertTrue(EventLinkRepository.incompatibility(Map.of("link_id", true, "event_id", false))
                .contains("missing native column(s)"));
    }

    @Test
    void ledgerRowIsDerivedWithMeasuredCounts() {
        List<ExtractionRun> recorded = new ArrayList<>();
        RunLedger.Sink previous = RunLedger.useSink(recorded::add);
        try {
            RunLedger.run("derive-native-event-links", () -> {
                RunLedger.record(new RunContext.EntityCount(EventLinkRepository.TABLE, 10, 8, 0, 0, 2,
                        null, null, null));
                return 0;
            });
            RunLedger.run("derive-native-event-links", () -> 5);
        } finally {
            RunLedger.useSink(previous);
            RunLedger.discard();
        }
        assertEquals("derived", recorded.get(0).sourceInterface());
        assertEquals("SUCCESS", recorded.get(0).status());
        assertEquals(10, recorded.get(0).extracted());
        assertEquals(8, recorded.get(0).inserted());
        assertEquals(0, recorded.get(0).updated());
        assertTrue(recorded.get(0).entityCountsJson().contains("\"entity\":\"kf_event_link\""));
        assertEquals("derived", recorded.get(1).sourceInterface());
        assertEquals("FAILED", recorded.get(1).status());
    }
}

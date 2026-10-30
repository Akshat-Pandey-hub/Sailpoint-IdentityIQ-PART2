package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.AppConfig;
import com.keyforge.part2.http.FetchOptions;
import com.keyforge.part2.http.KfAgentClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * D1 + D4 over real HTTP (an in-process mock KF Agent) into an in-memory keyed table: a full paged run marks the rows
 * the source no longer returns as deleted (and a returning row is revived), unchanged rows are skipped, and nothing is
 * marked after a caller-scoped run, a failed row, an empty result, or when the safety limit would be exceeded.
 */
class DeletionMarkingTest {

    private static final String[] KEYS = {"KFAGENT_BASE_URL", "KFAGENT_USERNAME", "KFAGENT_PASSWORD", "PG_HOST",
            "PG_DATABASE", "PG_USERNAME", "PG_PASSWORD"};

    /** The source population, in Part 1's order: "id=value". */
    private final List<String> source = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        assumeTrue(System.getenv("KFAGENT_BASE_URL") == null, "env config would override the mock server");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/kfagent/things", ex -> {
            String q = ex.getRequestURI().getQuery();
            queries.add(q);
            Map<String, String> p = new LinkedHashMap<>();
            for (String kv : q.split("&")) {
                p.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
            }
            int start = Integer.parseInt(p.getOrDefault("start", "0"));
            int limit = Integer.parseInt(p.getOrDefault("limit", "1000"));
            StringBuilder sb = new StringBuilder("[");
            for (int i = start; i < Math.min(source.size(), start + limit); i++) {
                String[] f = source.get(i).split("=");
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"id\":\"").append(f[0]).append("\",\"value\":\"").append(f[1]).append("\"}");
            }
            byte[] bytes = sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        System.setProperty("KFAGENT_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty("KFAGENT_USERNAME", "agent-user");
        System.setProperty("KFAGENT_PASSWORD", "agent-pass");
        System.setProperty("PG_HOST", "unused");
        System.setProperty("PG_DATABASE", "unused");
        System.setProperty("PG_USERNAME", "unused");
        System.setProperty("PG_PASSWORD", "unused");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        for (String k : KEYS) System.clearProperty(k);
    }

    /** An in-memory keyed table (uuid-shaped key from "id") plus the run's seen-key temp table, behind JDBC proxies. */
    private static final class Table implements EntityWriter {
        final Map<String, Object[]> rows = new LinkedHashMap<>(); // key -> {value, deleted}
        final Set<String> seen = new HashSet<>();
        final Set<String> failIds = new HashSet<>();
        boolean keyed = true;

        static String key(String id) {
            return String.format("00000000-0000-0000-0000-%012d", Integer.parseInt(id));
        }

        @Override public String entity() { return "Thing"; }
        @Override public String resource() { return "things"; }
        @Override public String targetTable() { return "s.kf_thing"; }
        @Override public void ensureSchemaAndTable(Connection conn) { }
        @Override public String ref(JsonNode row) { return row.path("id").asText(); }
        @Override public String key(JsonNode row) { return key(row.path("id").asText()); }
        @Override public String keyColumn() { return keyed ? "thingid" : null; }

        @Override
        public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String runId) throws SQLException {
            String id = row.path("id").asText();
            if (failIds.contains(id)) throw new SQLException("simulated failure");
            String value = row.path("value").asText();
            Object[] cur = rows.get(key(id));
            if (cur == null) {
                rows.put(key(id), new Object[]{value, false});
                return EntityRepository.Outcome.INSERTED;
            }
            if (cur[0].equals(value) && !(Boolean) cur[1]) {
                return EntityRepository.Outcome.SKIPPED; // unchanged live row (D4)
            }
            rows.put(key(id), new Object[]{value, false}); // changed, or revived
            return EntityRepository.Outcome.UPDATED;
        }

        long live() {
            return rows.values().stream().filter(r -> !(Boolean) r[1]).count();
        }

        boolean deleted(String id) {
            return (Boolean) rows.get(key(id))[1];
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "createStatement" -> statement();
                        case "prepareStatement" -> seenInsert((String) a[0]);
                        default -> defaultFor(m);
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                    (p, m, a) -> {
                        String sql = a == null ? null : (String) a[0];
                        switch (m.getName()) {
                            case "execute" -> {
                                if (sql.startsWith("TRUNCATE")) seen.clear();
                                return false;
                            }
                            case "executeQuery" -> {
                                assertTrue(sql.contains("FROM s.kf_thing AS t WHERE t.is_deleted = false"), sql);
                                int toMark = 0;
                                int live = 0;
                                for (Map.Entry<String, Object[]> r : rows.entrySet()) {
                                    if (!(Boolean) r.getValue()[1]) {
                                        live++;
                                        if (!seen.contains(r.getKey())) toMark++;
                                    }
                                }
                                return counts(toMark, live);
                            }
                            case "executeUpdate" -> {
                                assertTrue(sql.startsWith("UPDATE s.kf_thing AS t SET is_deleted = true"), sql);
                                assertTrue(sql.contains("s.k = t.thingid"), sql);
                                int marked = 0;
                                for (Map.Entry<String, Object[]> r : rows.entrySet()) {
                                    if (!(Boolean) r.getValue()[1] && !seen.contains(r.getKey())) {
                                        r.getValue()[1] = true;
                                        marked++;
                                    }
                                }
                                return marked;
                            }
                            default -> {
                                return defaultFor(m);
                            }
                        }
                    });
        }

        private PreparedStatement seenInsert(String sql) {
            assertTrue(sql.contains("kf_run_seen_keys"), sql);
            Map<Integer, Object> bound = new LinkedHashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> switch (m.getName()) {
                        case "setString" -> {
                            bound.put((Integer) a[0], a[1]);
                            yield null;
                        }
                        case "executeUpdate" -> seen.add((String) bound.get(1)) ? 1 : 0;
                        default -> defaultFor(m);
                    });
        }

        private static ResultSet counts(int first, int second) {
            boolean[] read = {false};
            return (ResultSet) Proxy.newProxyInstance(Table.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "next" -> {
                            boolean has = !read[0];
                            read[0] = true;
                            yield has;
                        }
                        case "getInt" -> (Integer) a[0] == 1 ? first : second;
                        default -> defaultFor(m);
                    });
        }

        private static Object defaultFor(Method m) {
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return Boolean.FALSE;
            if (r == int.class) return 0;
            return null;
        }
    }

    private EntityImportService service(Table table, int pageSize, int maxDeletePercent) {
        return new EntityImportService(new KfAgentClient(AppConfig.load()), table, pageSize, maxDeletePercent);
    }

    private void source(String... idEqualsValue) {
        source.clear();
        source.addAll(List.of(idEqualsValue));
    }

    @Test
    void aFullRunMarksRowsTheSourceNoLongerReturnsAndRevivesThemWhenTheyReturn() throws Exception {
        Table table = new Table();
        source("1=a", "2=b", "3=c", "4=d", "5=e");
        EntityImportService svc = service(table, 2, 50);
        ImportResult first = svc.run(table.connection(), "run-1");
        assertEquals(5, first.inserted());
        assertEquals(List.of("start=0&limit=2", "start=2&limit=2", "start=4&limit=2"), queries,
                "paged until the short page, next start = start + rows received");
        assertTrue(svc.lastSweep().swept());
        assertEquals(0, svc.lastSweep().marked());

        source("1=a", "2=b", "4=d", "5=e"); // 3 was deleted in IIQ
        ImportResult second = svc.run(table.connection(), "run-2");
        assertEquals(4, second.skipped(), "unchanged rows are skipped, not rewritten (D4)");
        assertEquals(1, svc.lastSweep().marked());
        assertTrue(table.deleted("3"), "row 3 is marked deleted, not removed");
        assertEquals(5, table.rows.size());
        assertEquals(4, table.live());

        source("1=a", "2=b", "3=c", "4=d", "5=e"); // 3 is back
        ImportResult third = svc.run(table.connection(), "run-3");
        assertEquals(1, third.updated(), "the returning row is revived by its write");
        assertFalse(table.deleted("3"));
        assertEquals(0, svc.lastSweep().marked());
    }

    @Test
    void aChangedValueIsUpdatedAndTheRestSkipped() throws Exception {
        Table table = new Table();
        source("1=a", "2=b");
        service(table, 10, 50).run(table.connection(), "run-1");
        source("1=a", "2=CHANGED");
        ImportResult r = service(table, 10, 50).run(table.connection(), "run-2");
        assertEquals(1, r.updated());
        assertEquals(1, r.skipped());
    }

    @Test
    void aCallerScopedRunNeverMarksAnything() throws Exception {
        Table table = new Table();
        source("1=a", "2=b", "3=c", "4=d");
        service(table, 10, 50).run(table.connection(), "full");
        EntityImportService svc = service(table, 10, 50);
        ImportResult page = svc.run(table.connection(), "page", new FetchOptions(0, 1, null, Map.of()));
        assertEquals(1, page.fetched());
        assertNull(svc.lastSweep(), "a page is a partial view");
        assertEquals(4, table.live());
    }

    @Test
    void aFailedRowBlocksMarking() throws Exception {
        Table table = new Table();
        source("1=a", "2=b", "3=c", "4=d");
        service(table, 10, 50).run(table.connection(), "full");
        table.failIds.add("1");
        source("1=a", "2=b", "3=c"); // 4 deleted, but row 1 fails this time
        EntityImportService svc = service(table, 10, 50);
        svc.run(table.connection(), "run-2");
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_ROW_FAILURES, svc.lastSweep().status());
        assertEquals(4, table.live(), "nothing is marked after a failed row");
    }

    @Test
    void theSafetyLimitStopsAMassMarkingUnlessRaised() throws Exception {
        Table table = new Table();
        source("1=a", "2=b", "3=c", "4=d");
        service(table, 10, 50).run(table.connection(), "full");
        source("1=a"); // only 1 of 4 came back: marking 3 of 4 = 75% > 50%
        EntityImportService svc = service(table, 10, 50);
        svc.run(table.connection(), "run-2");
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_SAFETY_LIMIT, svc.lastSweep().status());
        assertTrue(svc.lastSweep().note().contains("3 of 4"), svc.lastSweep().note());
        assertEquals(4, table.live());

        EntityImportService allowed = service(table, 10, 100); // a real mass deletion: raise the limit
        allowed.run(table.connection(), "run-3");
        assertEquals(3, allowed.lastSweep().marked());
        assertEquals(1, table.live());
    }

    @Test
    void anEmptySourceNeverMarksEveryRowDeleted() throws Exception {
        Table table = new Table();
        source("1=a");
        service(table, 10, 100).run(table.connection(), "full");
        source();
        EntityImportService svc = service(table, 10, 100);
        svc.run(table.connection(), "run-2");
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_EMPTY_KEEP, svc.lastSweep().status());
        assertEquals(1, table.live());
    }

    @Test
    void appendOnlyTablesAreNeverMarked() throws Exception {
        Table table = new Table();
        table.keyed = false; // e.g. audit events, syslog, archives, access history
        source("1=a");
        EntityImportService svc = service(table, 10, 50);
        svc.run(table.connection(), "run");
        assertNull(svc.lastSweep());

        assertTrue(EntityImportService.marksDeletions(new EntityRepository("s", AccountTable.SPEC)));
        assertTrue(EntityImportService.marksDeletions(new WorkgroupMemberRepository("s")));
        assertTrue(EntityImportService.marksDeletions(com.keyforge.part2.Main.identityWriter("s")));
        for (EntityWriter w : List.<EntityWriter>of(new AuditEventRepository("s"), new SyslogEventRepository("s"),
                new AccessHistoryEntitlementRepository("s"), new WorkItemArchiveRepository("s"),
                new CertificationArchiveRepository("s"))) {
            assertFalse(EntityImportService.marksDeletions(w), w.targetTable());
        }
    }

    /** Every keyed entity table exposes its primary key, so its full run can mark deletions. */
    @Test
    void everyCurrentStateTableExposesItsKeyColumn() {
        List<EntityWriter> writers = new ArrayList<>(List.of(new RoleEntitlementRepository("s"),
                new RoleHierarchyRepository("s"), new ProvisioningItemRepository("s"),
                new WorkflowDefinitionRepository("s"), new WorkgroupMemberRepository("s"),
                com.keyforge.part2.Main.identityWriter("s")));
        for (EntitySpec spec : List.of(ApplicationTable.SPEC, AccountTable.SPEC, EntitlementTable.SPEC, RoleTable.SPEC,
                IdentityRoleTable.SPEC, IdentityEntitlementTable.SPEC, AccountEntitlementTable.SPEC, WorkgroupTable.SPEC,
                GroupDefinitionTable.SPEC, IdentityRequestTable.SPEC, IdentityRequestItemTable.SPEC,
                IdentityRequestApprovalTable.SPEC, ProvisioningTxnTable.SPEC, WorkItemTable.SPEC, CertificationTable.SPEC,
                CertificationEntityTable.SPEC, CertificationItemTable.SPEC, PolicyTable.SPEC, PolicyConstraintTable.SPEC,
                PolicyViolationTable.SPEC, TaskResultTable.SPEC, TaskScheduleTable.SPEC)) {
            writers.add(new EntityRepository("s", spec));
        }
        for (EntityWriter w : writers) {
            assertTrue(EntityImportService.marksDeletions(w), w.targetTable());
        }
        assertEquals(28, writers.size());
    }
}

package com.keyforge.part2.ingest;

import com.keyforge.part2.http.FetchOptions;
import com.keyforge.part2.http.KfAgentClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlReportImportService} + {@link SqlReportRepository} against an in-memory report table: rows are keyed by
 * their uuid, so a repeated page is UPDATED or SKIPPED (never inserted again); full runs page through the report and
 * then soft-delete rows it no longer returns; caller-scoped runs never mark anything deleted; a duplicate key fails
 * the row; and an earlier row-numbered table is renamed, not dropped.
 */
class SqlReportImportServiceTest {

    private static final SqlReportSpec SPEC = WorkgroupMembersReport.SPEC;
    private static final int HASH_PARAM = 1 + SPEC.columns().size() + 1; // pk, the report columns, then record_hash

    /** The report table (uuid -> {hash, deleted}), the run's seen-key temp table, and the statements executed. */
    private static final class Db {
        final Map<String, Object[]> rows = new LinkedHashMap<>();
        final Set<String> seen = new HashSet<>();
        final List<String> ddl = new ArrayList<>();
        boolean tableExists;
        boolean legacyLayout;

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "createStatement" -> statement();
                        case "prepareStatement" -> prepared((String) a[0]);
                        default -> defaultFor(m);
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                    (p, m, a) -> {
                        String sql = a == null ? null : (String) a[0];
                        if (m.getName().equals("execute")) {
                            ddl.add(sql);
                            if (sql.startsWith("TRUNCATE")) seen.clear();
                            if (sql.contains("RENAME TO")) legacyLayout = false;
                            return false;
                        }
                        if (m.getName().equals("executeUpdate")) { // the soft-delete step
                            assertTrue(sql.startsWith("UPDATE ") && sql.contains("NOT EXISTS"), sql);
                            int marked = 0;
                            for (Map.Entry<String, Object[]> r : rows.entrySet()) {
                                if (!(Boolean) r.getValue()[1] && !seen.contains(r.getKey())) {
                                    r.getValue()[1] = true;
                                    marked++;
                                }
                            }
                            return marked;
                        }
                        return defaultFor(m);
                    });
        }

        private PreparedStatement prepared(String sql) {
            Map<Integer, Object> bound = new LinkedHashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "setString" -> {
                                bound.put((Integer) a[0], a[1]);
                                return null;
                            }
                            case "executeUpdate" -> { // INSERT INTO the seen-key temp table … ON CONFLICT DO NOTHING
                                assertTrue(sql.contains("kf_sql_report_seen"), sql);
                                return seen.add((String) bound.get(1)) ? 1 : 0;
                            }
                            case "executeQuery" -> {
                                if (sql.contains("information_schema.tables")) {
                                    return result(tableExists && SPEC.table().equals(bound.get(2)) ? 1 : 0, null);
                                }
                                if (sql.contains("information_schema.columns")) return result(legacyLayout ? 0 : 1, null);
                                return upsert(bound);
                            }
                            default -> {
                                return defaultFor(m);
                            }
                        }
                    });
        }

        /** ON CONFLICT DO UPDATE … WHERE hash changed OR deleted: one row (inserted?) or none (skipped). */
        private ResultSet upsert(Map<Integer, Object> bound) {
            String pk = (String) bound.get(1);
            String hash = (String) bound.get(HASH_PARAM);
            Object[] cur = rows.get(pk);
            if (cur == null) {
                rows.put(pk, new Object[]{hash, false});
                return result(1, true);
            }
            if (hash.equals(cur[0]) && !(Boolean) cur[1]) {
                return result(0, null);
            }
            rows.put(pk, new Object[]{hash, false});
            return result(1, false);
        }

        private static ResultSet result(int count, Boolean inserted) {
            int[] pos = {0};
            return (ResultSet) Proxy.newProxyInstance(Db.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "next" -> pos[0]++ < count;
                        case "getBoolean" -> inserted;
                        default -> defaultFor(m);
                    });
        }

        private static Object defaultFor(Method m) {
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return Boolean.FALSE;
            if (r == int.class) return 0;
            return null;
        }

        long live() {
            return rows.values().stream().filter(r -> !(Boolean) r[1]).count();
        }
    }

    /** A report served by offset like the KF Agent: the given members, each "workgroup|user|status". */
    private static final class Agent implements SqlReportImportService.Fetcher {
        final List<String> members = new ArrayList<>();
        final List<String> calls = new ArrayList<>();

        Agent(String... members) {
            this.members.addAll(List.of(members));
        }

        @Override
        public String fetch(String resource, FetchOptions o) {
            calls.add(o.start() + "/" + o.limit() + (o.filters().isEmpty() ? "" : "/" + o.filters())
                    + (o.modifiedAfter() == null ? "" : "/after=" + o.modifiedAfter()));
            int from = o.start() == null ? 0 : o.start();
            int to = o.limit() == null ? members.size() : Math.min(members.size(), from + o.limit());
            StringBuilder sb = new StringBuilder("[");
            for (int i = from; i < to; i++) {
                String[] f = members.get(i).split("\\|");
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"workgroup_name\":\"").append(f[0]).append("\",\"user_name\":\"").append(f[1])
                        .append("\",\"user_status\":\"").append(f[2]).append("\"}");
            }
            return sb.append(']').toString();
        }
    }

    private static SqlReportImportService service(Agent agent, int pageSize) {
        return new SqlReportImportService(agent, new SqlReportRepository("s", SPEC), pageSize);
    }

    private static FetchOptions page(int start, int limit) {
        return new FetchOptions(start, limit, null, Map.of());
    }

    @Test
    void theSamePageCalledAgainIsSkippedNotInsertedAgain() throws Exception {
        Db db = new Db();
        Agent agent = new Agent("WG1|alice|Active", "WG1|bob|Active", "WG2|carol|Active", "WG2|dave|Active");

        ImportResult first = service(agent, 500).run(db.connection(), "run-1", page(0, 2)).result();
        assertEquals(2, first.inserted());
        assertEquals(2, db.rows.size());

        for (int i = 0; i < 3; i++) {
            ImportResult again = service(agent, 500).run(db.connection(), "run-again", page(0, 2)).result();
            assertEquals(0, again.inserted(), "never inserted twice");
            assertEquals(2, again.skipped(), "unchanged rows are skipped");
        }
        assertEquals(2, db.rows.size(), "still two rows after four calls");

        agent.members.set(1, "WG1|bob|Disabled");
        ImportResult changed = service(agent, 500).run(db.connection(), "run-5", page(0, 2)).result();
        assertEquals(1, changed.updated(), "a changed value updates the same row");
        assertEquals(1, changed.skipped());
        assertEquals(2, db.rows.size());
    }

    @Test
    void consecutivePagesTogetherLoadTheWholeReport() throws Exception {
        Db db = new Db();
        Agent agent = new Agent("WG1|alice|Active", "WG1|bob|Active", "WG2|carol|Active", "WG2|dave|Active",
                "WG3|erin|Active");
        int total = 0;
        for (int start = 0; start < 6; start += 2) {
            SqlReportImportService.Outcome o = service(agent, 500).run(db.connection(), "run", page(start, 2));
            total += o.result().inserted();
            assertNull(o.sweep(), "a page never marks rows deleted");
        }
        assertEquals(5, total);
        assertEquals(5, db.rows.size());
        assertEquals(List.of("0/2", "2/2", "4/2"), agent.calls, "one Part 1 request per page, exactly as asked");
    }

    @Test
    void aFullRunPagesThroughTheReportAndMarksRowsItNoLongerReturns() throws Exception {
        Db db = new Db();
        Agent agent = new Agent("WG1|alice|Active", "WG1|bob|Active", "WG2|carol|Active", "WG2|dave|Active",
                "WG3|erin|Active");
        SqlReportImportService.Outcome first = service(agent, 2).run(db.connection(), "run-1", FetchOptions.none());
        assertEquals(List.of("0/2", "2/2", "4/2"), agent.calls, "next start = start + rows received; short page ends");
        assertEquals(5, first.result().inserted());
        assertTrue(first.sweep().swept());
        assertEquals(0, first.sweep().marked());

        agent.members.remove("WG1|bob|Active"); // bob left WG1
        SqlReportImportService.Outcome second = service(agent, 2).run(db.connection(), "run-2", FetchOptions.none());
        assertEquals(4, second.result().skipped(), "the remaining rows are unchanged");
        assertEquals(1, second.sweep().marked(), "bob's row is soft-deleted, not removed");
        assertEquals(5, db.rows.size());
        assertEquals(4, db.live());

        agent.members.add("WG1|bob|Active"); // bob is back
        SqlReportImportService.Outcome third = service(agent, 2).run(db.connection(), "run-3", FetchOptions.none());
        assertEquals(1, third.result().updated(), "the soft-deleted row is revived by its write");
        assertEquals(5, db.live());
    }

    @Test
    void aCallerScopedRunNeverMarksRowsDeleted() throws Exception {
        Db db = new Db();
        Agent agent = new Agent("WG1|alice|Active", "WG1|bob|Active", "WG2|carol|Active");
        service(agent, 500).run(db.connection(), "full", FetchOptions.none());
        SqlReportImportService.Outcome o = service(agent, 500).run(db.connection(), "page",
                new FetchOptions(null, null, null, Map.of("workgroup_name", "WG2")));
        assertNull(o.sweep());
        assertEquals(3, db.live(), "rows outside the filtered result are untouched");
        assertEquals("null/null/{workgroup_name=WG2}", agent.calls.get(1), "filters are forwarded to Part 1");
    }

    @Test
    void modifiedAfterIsForwardedToPartOne() throws Exception {
        Db db = new Db();
        Agent agent = new Agent("WG1|alice|Active");
        service(agent, 500).run(db.connection(), "run", new FetchOptions(0, 10, "2026-10-01T00:00:00Z", Map.of()));
        assertEquals(List.of("0/10/after=2026-10-01T00:00:00Z"), agent.calls);
    }

    @Test
    void aDuplicateKeyInOneRunFailsTheRowAndBlocksDeletionMarking() throws Exception {
        Db db = new Db();
        Agent agent = new Agent("WG1|alice|Active", "WG1|alice|Disabled", "WG2|carol|Active");
        SqlReportImportService.Outcome o = service(agent, 500).run(db.connection(), "run", FetchOptions.none());
        assertEquals(1, o.result().failed(), "the second row with the same key is not written over the first");
        assertEquals(2, o.result().inserted());
        assertFalse(o.sweep().swept(), "no row is marked deleted after a failed row");
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_ROW_FAILURES, o.sweep().status());
    }

    @Test
    void anEmptyReportNeverMarksEveryRowDeleted() throws Exception {
        Db db = new Db();
        Agent agent = new Agent("WG1|alice|Active");
        service(agent, 500).run(db.connection(), "run-1", FetchOptions.none());
        agent.members.clear();
        SqlReportImportService.Outcome o = service(agent, 500).run(db.connection(), "run-2", FetchOptions.none());
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_EMPTY_KEEP, o.sweep().status());
        assertEquals(1, db.live());
    }

    @Test
    void anHttpOrBodyFailurePropagatesAndMarksNothing() {
        Db db = new Db();
        SqlReportImportService.Fetcher down = (r, o) -> { throw new KfAgentClient.KfAgentHttpException("HTTP 502"); };
        assertThrows(KfAgentClient.KfAgentHttpException.class, () -> new SqlReportImportService(down,
                new SqlReportRepository("s", SPEC), 500).run(db.connection(), "run", FetchOptions.none()));
        SqlReportImportService.Fetcher error = (r, o) -> "{\"error\":\"invalid report request\"}";
        assertThrows(KfAgentClient.KfAgentHttpException.class, () -> new SqlReportImportService(error,
                new SqlReportRepository("s", SPEC), 500).run(db.connection(), "run", FetchOptions.none()));
        assertTrue(db.rows.isEmpty());
    }

    @Test
    void anOldRowNumberedTableIsRenamedNotDropped() throws Exception {
        Db db = new Db();
        db.tableExists = true;
        db.legacyLayout = true;
        service(new Agent("WG1|alice|Active"), 500).run(db.connection(), "run", page(0, 1));
        assertTrue(db.ddl.contains("ALTER TABLE s.kf_workgroup_members RENAME TO kf_workgroup_members_legacy"),
                String.valueOf(db.ddl));
        assertTrue(db.ddl.stream().noneMatch(s -> s.startsWith("DROP TABLE s.") || s.startsWith("DELETE")));
    }
}

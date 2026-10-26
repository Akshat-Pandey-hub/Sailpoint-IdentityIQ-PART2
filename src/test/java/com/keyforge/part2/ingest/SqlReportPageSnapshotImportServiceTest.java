package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.FetchOptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlReportPageSnapshotImportServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fetchesOneExactPageAndPersistsItsRowsAsAnAppendOnlySnapshot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FetchOptions requested = new FetchOptions(12, 2, null, Map.of());
        SqlReportPageSnapshotImportService.PageFetcher fetcher = (resource, options) -> {
            calls.incrementAndGet();
            assertEquals("workgroup-members", resource);
            assertEquals(requested, options);
            return "[{\"workgroup_name\":\"Engineering\",\"user_name\":\"alice\"},"
                    + "{\"workgroup_name\":\"Engineering\",\"user_name\":\"bob\"}]";
        };
        SqlReportPageSnapshotRepository repository = new SqlReportPageSnapshotRepository("part2_test");
        JdbcStub jdbc = new JdbcStub();
        SqlReportPageSnapshotImportService service = new SqlReportPageSnapshotImportService(fetcher, repository);

        ImportResult result = service.run(jdbc.connection(), "WorkgroupMembers", "workgroup-members",
                "run-page-1", requested);

        assertEquals(1, calls.get(), "explicit page makes exactly one Part 1 request");
        assertEquals(1, result.pages());
        assertEquals(2, result.fetched());
        assertEquals(2, result.inserted());
        assertEquals(0, result.updated());
        assertEquals(repository.rowTable(), result.targetTable());
        assertNotNull(service.lastSnapshotId());
        assertEquals(List.of("autoCommit=false", "executeUpdate", "addBatch", "addBatch", "executeBatch",
                "commit", "autoCommit=true"), jdbc.events);

        String headerSql = jdbc.preparedSql.get(0);
        assertTrue(headerSql.contains("INSERT INTO part2_test.kf_sql_report_page_snapshot"));
        assertEquals(service.lastSnapshotId().toString(), jdbc.updates.get(0).get(1));
        assertEquals("WorkgroupMembers", jdbc.updates.get(0).get(2));
        assertEquals("workgroup-members", jdbc.updates.get(0).get(3));
        assertEquals("run-page-1", jdbc.updates.get(0).get(4));
        assertEquals(12, jdbc.updates.get(0).get(5));
        assertEquals(2, jdbc.updates.get(0).get(6));
        assertEquals(2, jdbc.updates.get(0).get(7));

        assertTrue(jdbc.preparedSql.get(1).contains("INSERT INTO part2_test.kf_sql_report_page_snapshot_row"));
        assertEquals(0, jdbc.batches.get(0).get(2));
        assertEquals(mapper.readTree("{\"workgroup_name\":\"Engineering\",\"user_name\":\"alice\"}"),
                mapper.readTree((String) jdbc.batches.get(0).get(3)));
        assertEquals(1, jdbc.batches.get(1).get(2));
        assertTrue(jdbc.statements.stream().noneMatch(s -> s.matches("(?is)^\\s*(DELETE|UPDATE|TRUNCATE)\\b.*")),
                "page persistence must not replace or sweep canonical report rows");
    }

    @Test
    void emptyPageStillRecordsAnIndependentSnapshotHeader() throws Exception {
        SqlReportPageSnapshotRepository repository = new SqlReportPageSnapshotRepository("part2_test");
        JdbcStub jdbc = new JdbcStub();
        SqlReportPageSnapshotImportService service = new SqlReportPageSnapshotImportService(
                (resource, options) -> "[]", repository);

        ImportResult result = service.run(jdbc.connection(), "EntitlementAssignment", "entitlement-assignment",
                "run-empty", new FetchOptions(20, 2, null, Map.of()));

        assertEquals(0, result.fetched());
        assertEquals(0, result.inserted());
        assertNotNull(service.lastSnapshotId());
        assertEquals(1, jdbc.updates.size(), "empty pages still insert a snapshot header");
        assertTrue(jdbc.preparedSql.stream().noneMatch(s -> s.contains(SqlReportPageSnapshotRepository.ROW_TABLE)));
        assertTrue(jdbc.events.contains("commit"));
    }

    @Test
    void rejectsAnythingButAnExplicitPageBeforeFetchingOrWriting() throws Exception {
        SqlReportPageSnapshotRepository repository = new SqlReportPageSnapshotRepository("part2_test");
        AtomicInteger calls = new AtomicInteger();
        SqlReportPageSnapshotImportService service = new SqlReportPageSnapshotImportService(
                (resource, options) -> { calls.incrementAndGet(); return "[]"; }, repository);

        assertThrows(IllegalArgumentException.class, () -> service.run(null, "report", "resource", "run",
                FetchOptions.none()));
        assertThrows(IllegalArgumentException.class, () -> service.run(null, "report", "resource", "run",
                new FetchOptions(0, 2, null, Map.of("name", "alice"))));
        assertEquals(0, calls.get());
    }

    @Test
    void rowWriteFailureRollsBackTheSnapshotHeaderAndPageTogether() throws Exception {
        SqlReportPageSnapshotRepository repository = new SqlReportPageSnapshotRepository("part2_test");
        JdbcStub jdbc = new JdbcStub();
        jdbc.failBatch = true;
        SqlReportPageSnapshotImportService service = new SqlReportPageSnapshotImportService(
                (resource, options) -> "[{\"user_name\":\"alice\"}]", repository);

        assertThrows(SQLException.class, () -> service.run(jdbc.connection(), "WorkgroupMembers", "workgroup-members",
                "run-fail", new FetchOptions(0, 2, null, Map.of())));
        assertTrue(jdbc.events.contains("rollback"));
        assertFalse(jdbc.events.contains("commit"));
    }

    @Test
    void rejectsMalformedOrOversizedSourcePagesBeforeDatabaseWrites() throws Exception {
        SqlReportPageSnapshotRepository repository = new SqlReportPageSnapshotRepository("part2_test");
        FetchOptions options = new FetchOptions(0, 2, null, Map.of());
        for (String body : List.of("[{\"row\":1},{\"row\":2},{\"row\":3}]", "[null]")) {
            JdbcStub jdbc = new JdbcStub();
            SqlReportPageSnapshotImportService service = new SqlReportPageSnapshotImportService(
                    (resource, requested) -> body, repository);
            assertThrows(com.keyforge.part2.http.KfAgentClient.KfAgentHttpException.class,
                    () -> service.run(jdbc.connection(), "report", "resource", "run", options));
            assertTrue(jdbc.events.isEmpty());
            assertTrue(jdbc.statements.isEmpty());
        }
    }

    private static final class JdbcStub {
        final List<String> events = new ArrayList<>();
        final List<String> statements = new ArrayList<>();
        final List<String> preparedSql = new ArrayList<>();
        final List<Map<Integer, Object>> updates = new ArrayList<>();
        final List<Map<Integer, Object>> batches = new ArrayList<>();
        private boolean autoCommit = true;
        boolean failBatch;

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAutoCommit" -> autoCommit;
                        case "setAutoCommit" -> {
                            autoCommit = (Boolean) args[0];
                            events.add("autoCommit=" + autoCommit);
                            yield null;
                        }
                        case "createStatement" -> statement();
                        case "prepareStatement" -> prepared((String) args[0]);
                        case "commit", "rollback" -> { events.add(method.getName()); yield null; }
                        default -> defaultValue(method);
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("execute")) {
                            statements.add((String) args[0]);
                            return false;
                        }
                        return defaultValue(method);
                    });
        }

        private PreparedStatement prepared(String sql) {
            preparedSql.add(sql);
            Map<Integer, Object> bound = new LinkedHashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setString", "setInt" -> bound.put((Integer) args[0], args[1]);
                            case "executeUpdate" -> { updates.add(new LinkedHashMap<>(bound)); events.add("executeUpdate"); return 1; }
                            case "addBatch" -> { batches.add(new LinkedHashMap<>(bound)); events.add("addBatch"); }
                            case "executeBatch" -> {
                                events.add("executeBatch");
                                if (failBatch) throw new SQLException("simulated page-row insert failure");
                                return new int[batches.size()];
                            }
                            default -> { }
                        }
                        return defaultValue(method);
                    });
        }

        private static Object defaultValue(Method method) {
            Class<?> type = method.getReturnType();
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }
}

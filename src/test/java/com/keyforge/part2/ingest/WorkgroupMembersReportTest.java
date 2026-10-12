package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_workgroup_members (SQL report "Query 2"; NOT the native edge table kf_workgroup_member). Pins Part 1's column
 * contract (7 SELECT-alias text columns, in order, as iiq_native.sql) and proves that the WHOLE live
 * {@code /kfagent/workgroup-members} response is stored exactly as Part 1 stored it (all 6 rows of
 * iiq_native.kf_workgroup_members in the dump: 2 'Active' member rows and 4 'No Members' rows with NULL member
 * columns). The shared replace-all / rollback / fetch-first behaviour is covered by
 * {@link EntitlementAssignmentReportTest}.
 */
class WorkgroupMembersReportTest {

    /** Column order of iiq_native.kf_workgroup_members exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "id", "workgroup_name", "user_name", "display_name", "first_name", "last_name", "email", "user_status",
            "source_system", "source_interface", "extraction_run_id", "extracted_at");

    /** The full live KF Agent response (verbatim). */
    private static final String LIVE_PAGE = "["
            + "{\"workgroup_name\":\"AdminCap\",\"user_name\":\"spadmin\",\"display_name\":\"Molly J\",\"first_name\":\"Molly\","
            + "\"last_name\":\"J\",\"email\":\"MoliJangada@vardhaman310.onmicrosoft.com\",\"user_status\":\"Active\"},"
            + "{\"workgroup_name\":\"Adminq\",\"user_name\":\"spadmin\",\"display_name\":\"Molly J\",\"first_name\":\"Molly\","
            + "\"last_name\":\"J\",\"email\":\"MoliJangada@vardhaman310.onmicrosoft.com\",\"user_status\":\"Active\"},"
            + "{\"workgroup_name\":\"AttributeSyncWorkGroup\",\"user_name\":null,\"display_name\":null,\"first_name\":null,"
            + "\"last_name\":null,\"email\":null,\"user_status\":\"No Members\"},"
            + "{\"workgroup_name\":\"RapidSetup Error Notification\",\"user_name\":null,\"display_name\":null,\"first_name\":null,"
            + "\"last_name\":null,\"email\":null,\"user_status\":\"No Members\"},"
            + "{\"workgroup_name\":\"RapidSetup No Manager\",\"user_name\":null,\"display_name\":null,\"first_name\":null,"
            + "\"last_name\":null,\"email\":null,\"user_status\":\"No Members\"},"
            + "{\"workgroup_name\":\"SelfRegistrationWorkGroup\",\"user_name\":null,\"display_name\":null,\"first_name\":null,"
            + "\"last_name\":null,\"email\":null,\"user_status\":\"No Members\"}]";

    /** All rows as Part 1 stored them (dump COPY values in id order; null = \N), then source_system, source_interface. */
    private static final List<List<String>> PART1_STORED = List.of(
            Arrays.asList("AdminCap", "spadmin", "Molly J", "Molly",
                    "J", "MoliJangada@vardhaman310.onmicrosoft.com", "Active", "IdentityIQ", "native_iiq_jdbc"),
            Arrays.asList("Adminq", "spadmin", "Molly J", "Molly",
                    "J", "MoliJangada@vardhaman310.onmicrosoft.com", "Active", "IdentityIQ", "native_iiq_jdbc"),
            Arrays.asList("AttributeSyncWorkGroup", null, null, null,
                    null, null, "No Members", "IdentityIQ", "native_iiq_jdbc"),
            Arrays.asList("RapidSetup Error Notification", null, null, null,
                    null, null, "No Members", "IdentityIQ", "native_iiq_jdbc"),
            Arrays.asList("RapidSetup No Manager", null, null, null,
                    null, null, "No Members", "IdentityIQ", "native_iiq_jdbc"),
            Arrays.asList("SelfRegistrationWorkGroup", null, null, null,
                    null, null, "No Members", "IdentityIQ", "native_iiq_jdbc"));

    private final SqlReportRepository repo = new SqlReportRepository("iiq_native_part2_test",
            WorkgroupMembersReport.TABLE, WorkgroupMembersReport.COLUMNS);

    @Test
    void ddlMatchesIiqNativeTextColumnsAndIdentityId() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS iiq_native_part2_test.kf_workgroup_members ("));
        assertTrue(ddl.contains("id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY"));
        assertTrue(ddl.contains("email text") && ddl.contains("user_status text"),
                "every report value is text");
        assertFalse(ddl.contains("record_hash") || ddl.contains("source_object_type") || ddl.contains("is_deleted"),
                "a SQL-report table has no hash, object type or soft delete");
        assertEquals("workgroup-members", WorkgroupMembersReport.RESOURCE);
    }

    @Test
    void liveRowsAreStoredExactlyAsPart1StoredThem() throws Exception {
        List<String> calls = new ArrayList<>();
        SqlReportImportService.PageFetcher pages = (resource, start, limit) -> {
            calls.add(resource + "?start=" + start + "&limit=" + limit);
            return LIVE_PAGE;
        };
        Recorder rec = new Recorder();
        ImportResult result = new SqlReportImportService(pages, WorkgroupMembersReport.RESOURCE, repo, 500)
                .run(rec.connection(), "WorkgroupMembers", "run-q2");

        assertEquals(List.of("workgroup-members?start=0&limit=500"), calls);
        assertEquals(6, result.fetched());
        assertEquals(6, result.inserted());
        assertTrue(rec.sql.contains("DELETE FROM iiq_native_part2_test.kf_workgroup_members"));
        assertTrue(rec.sql.contains("commit"));
        assertEquals(6, rec.batches.size());
        for (int r = 0; r < 6; r++) {
            Map<Integer, Object> b = rec.batches.get(r);
            List<String> expected = PART1_STORED.get(r);
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), b.get(i + 1), "row " + r + " position " + (i + 1));
            }
            assertEquals("run-q2", b.get(10));
            assertEquals(10, b.size(), "7 report columns + 3 lineage values");
        }
    }

    @Test
    void insertBindsTheSevenColumnsInSelectOrder() throws Exception {
        assertEquals("INSERT INTO iiq_native_part2_test.kf_workgroup_members (workgroup_name, user_name, display_name, "
                + "first_name, last_name, email, user_status, source_system, source_interface, extraction_run_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", repo.insertSql());
        JsonNode numeric = new ObjectMapper().readTree("{\"user_status\":\"Active\",\"workgroup_name\":42}");
        Recorder rec = new Recorder();
        repo.replaceAll(rec.connection(), List.of(numeric), "run-q2b");
        assertEquals("42", rec.batches.get(0).get(1), "values are stored as text");
        assertEquals("Active", rec.batches.get(0).get(7));
    }

    @Test
    void secondRunReplacesTheWholeSnapshot() throws Exception {
        SqlReportImportService.PageFetcher pages = (resource, start, limit) -> LIVE_PAGE;
        Recorder rec = new Recorder();
        rec.existingRows = 6; // the first run's rows
        SqlReportImportService service =
                new SqlReportImportService(pages, WorkgroupMembersReport.RESOURCE, repo, 500);
        ImportResult result = service.run(rec.connection(), "WorkgroupMembers", "run-q2-second");

        assertEquals(6, service.lastDeleted(), "previous snapshot removed");
        assertEquals(6, result.inserted(), "latest snapshot written, same row count");
        int del = rec.sql.indexOf("DELETE FROM iiq_native_part2_test.kf_workgroup_members");
        assertTrue(del >= 0 && del < rec.sql.indexOf("commit"), "delete and insert share one transaction");
    }

    /** Minimal JDBC recorder: statements executed, commit/rollback, and each batched row's bound values. */
    private static final class Recorder {
        final List<String> sql = new ArrayList<>();
        final List<Map<Integer, Object>> batches = new ArrayList<>();
        int existingRows;

        Connection connection() {
            boolean[] auto = {true};
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getAutoCommit" -> auto[0];
                        case "setAutoCommit" -> {
                            auto[0] = (Boolean) a[0];
                            yield null;
                        }
                        case "commit", "rollback" -> {
                            sql.add(m.getName());
                            yield null;
                        }
                        case "createStatement" -> statement();
                        case "prepareStatement" -> prepared();
                        default -> defaultFor(m);
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                    (p, m, a) -> {
                        if (m.getName().equals("execute") || m.getName().equals("executeUpdate")) {
                            sql.add((String) a[0]);
                        }
                        if (m.getName().equals("executeUpdate")) {
                            return existingRows;
                        }
                        return defaultFor(m);
                    });
        }

        private PreparedStatement prepared() {
            Map<Integer, Object> bound = new LinkedHashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "setString" -> bound.put((Integer) a[0], a[1]);
                            case "addBatch" -> {
                                batches.add(new LinkedHashMap<>(bound));
                                bound.clear();
                            }
                            case "executeBatch" -> {
                                return new int[0];
                            }
                            default -> { }
                        }
                        return defaultFor(m);
                    });
        }

        private static Object defaultFor(Method method) {
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return Boolean.FALSE;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            return null;
        }
    }
}

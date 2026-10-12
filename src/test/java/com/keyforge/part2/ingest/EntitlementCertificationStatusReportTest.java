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
 * kf_entitlement_certification_status (SQL report "Query 4"). Pins Part 1's column contract (14 SELECT-alias text
 * columns, in order, as iiq_native.sql) and proves that real live {@code /kfagent/entitlement-certification-status}
 * rows are stored exactly as Part 1 stored them (iiq_native.kf_entitlement_certification_status rows 1, 348 and 416 of
 * the dump: a never-certified row, an assignedRoles row with NULL application/account from the LEFT JOIN, and the
 * decided row with certification name / date / certifier / decision). The shared replace-all / rollback / fetch-first
 * behaviour is covered by {@link EntitlementAssignmentReportTest}.
 */
class EntitlementCertificationStatusReportTest {

    /** Column order of iiq_native.kf_entitlement_certification_status exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "id", "user_id", "user_name", "manager_user_id", "manager_name", "application_name", "account_name",
            "entitlement_attribute", "entitlement_value", "entitlement_assigned_date", "last_certification_name",
            "last_certified_date", "certifier", "certification_decision", "never_certified",
            "source_system", "source_interface", "extraction_run_id", "extracted_at");

    /** Live KF Agent response rows (verbatim). */
    private static final String LIVE_PAGE = "["
            + "{\"user_id\":\"Alexander Bailey\",\"user_name\":\"Alexander Bailey\",\"manager_user_id\":null,\"manager_name\":null,\"application_name\":\"EntraAuth\","
            + "\"account_name\":\"54f9a344-22a1-4220-aa08-b19f1f8a3633\",\"entitlement_attribute\":\"groups\",\"entitlement_value\":\"001ec823-2a04-4a63-90b8-3882abffbcfb\",\"entitlement_assigned_date\":\"2025-07-27 03:27:26.258+00\",\"last_certification_name\":null,"
            + "\"last_certified_date\":null,\"certifier\":null,\"certification_decision\":null,\"never_certified\":\"YES\"},"
            + "{\"user_id\":\"App_IDJ0001000\",\"user_name\":\"Ethan Reyes\",\"manager_user_id\":null,\"manager_name\":null,\"application_name\":null,"
            + "\"account_name\":null,\"entitlement_attribute\":\"assignedRoles\",\"entitlement_value\":\"Engineering-Base\",\"entitlement_assigned_date\":\"2026-08-03 15:20:36.181+00\",\"last_certification_name\":null,"
            + "\"last_certified_date\":null,\"certifier\":null,\"certification_decision\":null,\"never_certified\":\"YES\"},"
            + "{\"user_id\":\"Alexander Evans\",\"user_name\":\"Alexander Evans\",\"manager_user_id\":null,\"manager_name\":null,\"application_name\":\"EntraTarget\","
            + "\"account_name\":\"179fcc53-99af-43e1-92c6-38c46c6af4ee\",\"entitlement_attribute\":\"groups\",\"entitlement_value\":\"00edc4ac-9a67-4e7e-abe3-f84d9e8ffee5\",\"entitlement_assigned_date\":\"2025-07-27 03:35:00.403+00\",\"last_certification_name\":\"Targeted Access Review for Alexander Evans\","
            + "\"last_certified_date\":\"2026-10-02 15:23:51.988+00\",\"certifier\":\"spadmin\",\"certification_decision\":\"Approved\",\"never_certified\":\"NO\"}]";

    /** The same rows as Part 1 stored them (dump COPY values; null = \N), followed by source_system, source_interface. */
    private static final List<List<String>> PART1_STORED = List.of(
            Arrays.asList("Alexander Bailey", "Alexander Bailey", null, null,
                    "EntraAuth", "54f9a344-22a1-4220-aa08-b19f1f8a3633", "groups", "001ec823-2a04-4a63-90b8-3882abffbcfb", "2025-07-27 03:27:26.258+00",
                    null, null, null, null, "YES", "IdentityIQ", "native_iiq_jdbc"),
            Arrays.asList("App_IDJ0001000", "Ethan Reyes", null, null,
                    null, null, "assignedRoles", "Engineering-Base", "2026-08-03 15:20:36.181+00",
                    null, null, null, null, "YES", "IdentityIQ", "native_iiq_jdbc"),
            Arrays.asList("Alexander Evans", "Alexander Evans", null, null,
                    "EntraTarget", "179fcc53-99af-43e1-92c6-38c46c6af4ee", "groups", "00edc4ac-9a67-4e7e-abe3-f84d9e8ffee5", "2025-07-27 03:35:00.403+00",
                    "Targeted Access Review for Alexander Evans", "2026-10-02 15:23:51.988+00", "spadmin", "Approved", "NO", "IdentityIQ", "native_iiq_jdbc"));

    private final SqlReportRepository repo = new SqlReportRepository("iiq_native_part2_test",
            EntitlementCertificationStatusReport.TABLE, EntitlementCertificationStatusReport.COLUMNS);

    @Test
    void ddlMatchesIiqNativeTextColumnsAndIdentityId() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS iiq_native_part2_test.kf_entitlement_certification_status ("));
        assertTrue(ddl.contains("id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY"));
        assertTrue(ddl.contains("last_certified_date text") && ddl.contains("never_certified text"),
                "every report value is text");
        assertFalse(ddl.contains("record_hash") || ddl.contains("source_object_type") || ddl.contains("is_deleted"),
                "a SQL-report table has no hash, object type or soft delete");
        assertEquals("entitlement-certification-status", EntitlementCertificationStatusReport.RESOURCE);
    }

    @Test
    void liveRowsAreStoredExactlyAsPart1StoredThem() throws Exception {
        List<String> calls = new ArrayList<>();
        SqlReportImportService.PageFetcher pages = (resource, start, limit) -> {
            calls.add(resource + "?start=" + start + "&limit=" + limit);
            return LIVE_PAGE;
        };
        Recorder rec = new Recorder();
        ImportResult result = new SqlReportImportService(pages, EntitlementCertificationStatusReport.RESOURCE, repo, 500)
                .run(rec.connection(), "EntitlementCertificationStatus", "run-q4");

        assertEquals(List.of("entitlement-certification-status?start=0&limit=500"), calls);
        assertEquals(3, result.fetched());
        assertEquals(3, result.inserted());
        assertTrue(rec.sql.contains("DELETE FROM iiq_native_part2_test.kf_entitlement_certification_status"));
        assertTrue(rec.sql.contains("commit"));
        assertEquals(3, rec.batches.size());
        for (int r = 0; r < 3; r++) {
            Map<Integer, Object> b = rec.batches.get(r);
            List<String> expected = PART1_STORED.get(r);
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), b.get(i + 1), "row " + r + " position " + (i + 1));
            }
            assertEquals("run-q4", b.get(17));
            assertEquals(17, b.size(), "14 report columns + 3 lineage values");
        }
    }

    @Test
    void insertBindsTheFourteenColumnsInSelectOrder() throws Exception {
        assertEquals("INSERT INTO iiq_native_part2_test.kf_entitlement_certification_status (user_id, user_name, "
                + "manager_user_id, manager_name, application_name, account_name, entitlement_attribute, "
                + "entitlement_value, entitlement_assigned_date, last_certification_name, last_certified_date, "
                + "certifier, certification_decision, never_certified, source_system, source_interface, "
                + "extraction_run_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", repo.insertSql());
        JsonNode numeric = new ObjectMapper().readTree("{\"never_certified\":\"YES\",\"user_id\":42}");
        Recorder rec = new Recorder();
        repo.replaceAll(rec.connection(), List.of(numeric), "run-q4b");
        assertEquals("42", rec.batches.get(0).get(1), "values are stored as text");
        assertEquals("YES", rec.batches.get(0).get(14));
    }

    /** Minimal JDBC recorder: statements executed, commit/rollback, and each batched row's bound values. */
    private static final class Recorder {
        final List<String> sql = new ArrayList<>();
        final List<Map<Integer, Object>> batches = new ArrayList<>();

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

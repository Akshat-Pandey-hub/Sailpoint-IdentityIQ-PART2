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
 * kf_entitlement_certification (SQL report "Query 3"). Pins Part 1's column contract (9 SELECT-alias text columns, in
 * order, as iiq_native.sql) and proves that real live {@code /kfagent/entitlement-certification} rows are stored
 * exactly as Part 1 stored them (iiq_native.kf_entitlement_certification rows 1 and 368 of the dump, incl. the JSON
 * null modified date → SQL NULL and the 'NO' flag). The shared replace-all / rollback / fetch-first behaviour is
 * covered by {@link EntitlementAssignmentReportTest}.
 */
class EntitlementCertificationReportTest {

    /** Column order of iiq_native.kf_entitlement_certification exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "id", "user_id", "user_name", "application_name", "account_name", "entitlement_attribute",
            "entitlement_value", "entitlement_assigned_date", "entitlement_modified_date", "never_certified",
            "source_system", "source_interface", "extraction_run_id", "extracted_at");

    /** Live KF Agent response rows (verbatim). */
    private static final String LIVE_PAGE = "["
            + "{\"user_id\":\"Alexander Bailey\",\"user_name\":\"Alexander Bailey\",\"application_name\":\"EntraAuth\","
            + "\"account_name\":\"54f9a344-22a1-4220-aa08-b19f1f8a3633\",\"entitlement_attribute\":\"groups\","
            + "\"entitlement_value\":\"001ec823-2a04-4a63-90b8-3882abffbcfb\","
            + "\"entitlement_assigned_date\":\"2025-07-27 03:27:26.258+00\",\"entitlement_modified_date\":null,"
            + "\"never_certified\":\"YES\"},"
            + "{\"user_id\":\"Alexander Cook\",\"user_name\":\"Alexander Cook\",\"application_name\":\"EntraTarget\","
            + "\"account_name\":\"881d4680-0db5-4024-84df-7b6ed93c3aeb\",\"entitlement_attribute\":\"groups\","
            + "\"entitlement_value\":\"001ec823-2a04-4a63-90b8-3882abffbcfb\","
            + "\"entitlement_assigned_date\":\"2025-07-27 03:35:00.494+00\","
            + "\"entitlement_modified_date\":\"2026-09-15 16:09:17.138+00\",\"never_certified\":\"NO\"}]";

    /** The same rows as Part 1 stored them (dump COPY values; null = \N), followed by source_system, source_interface. */
    private static final List<List<String>> PART1_STORED = List.of(
            Arrays.asList("Alexander Bailey", "Alexander Bailey", "EntraAuth", "54f9a344-22a1-4220-aa08-b19f1f8a3633",
                    "groups", "001ec823-2a04-4a63-90b8-3882abffbcfb", "2025-07-27 03:27:26.258+00", null, "YES",
                    "IdentityIQ", "native_iiq_jdbc"),
            Arrays.asList("Alexander Cook", "Alexander Cook", "EntraTarget", "881d4680-0db5-4024-84df-7b6ed93c3aeb",
                    "groups", "001ec823-2a04-4a63-90b8-3882abffbcfb", "2025-07-27 03:35:00.494+00",
                    "2026-09-15 16:09:17.138+00", "NO", "IdentityIQ", "native_iiq_jdbc"));

    private final SqlReportRepository repo = new SqlReportRepository("iiq_native_part2_test",
            EntitlementCertificationReport.TABLE, EntitlementCertificationReport.COLUMNS);

    @Test
    void ddlMatchesIiqNativeTextColumnsAndIdentityId() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS iiq_native_part2_test.kf_entitlement_certification ("));
        assertTrue(ddl.contains("id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY"));
        assertTrue(ddl.contains("entitlement_assigned_date text") && ddl.contains("never_certified text"),
                "every report value is text");
        assertFalse(ddl.contains("record_hash") || ddl.contains("source_object_type") || ddl.contains("is_deleted"),
                "a SQL-report table has no hash, object type or soft delete");
        assertEquals("entitlement-certification", EntitlementCertificationReport.RESOURCE);
    }

    @Test
    void liveRowsAreStoredExactlyAsPart1StoredThem() throws Exception {
        List<String> calls = new ArrayList<>();
        SqlReportImportService.PageFetcher pages = (resource, start, limit) -> {
            calls.add(resource + "?start=" + start + "&limit=" + limit);
            return LIVE_PAGE;
        };
        Recorder rec = new Recorder();
        ImportResult result = new SqlReportImportService(pages, EntitlementCertificationReport.RESOURCE, repo, 500)
                .run(rec.connection(), "EntitlementCertification", "run-q3");

        assertEquals(List.of("entitlement-certification?start=0&limit=500"), calls);
        assertEquals(2, result.fetched());
        assertEquals(2, result.inserted());
        assertTrue(rec.sql.contains("DELETE FROM iiq_native_part2_test.kf_entitlement_certification"));
        assertTrue(rec.sql.contains("commit"));
        assertEquals(2, rec.batches.size());
        for (int r = 0; r < 2; r++) {
            Map<Integer, Object> b = rec.batches.get(r);
            List<String> expected = PART1_STORED.get(r);
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), b.get(i + 1), "row " + r + " position " + (i + 1));
            }
            assertEquals("run-q3", b.get(12));
            assertEquals(12, b.size(), "9 report columns + 3 lineage values");
        }
    }

    @Test
    void insertBindsTheNineColumnsInSelectOrder() throws Exception {
        assertEquals("INSERT INTO iiq_native_part2_test.kf_entitlement_certification (user_id, user_name, "
                + "application_name, account_name, entitlement_attribute, entitlement_value, entitlement_assigned_date, "
                + "entitlement_modified_date, never_certified, source_system, source_interface, extraction_run_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", repo.insertSql());
        JsonNode numeric = new ObjectMapper().readTree("{\"never_certified\":\"YES\",\"user_id\":42}");
        Recorder rec = new Recorder();
        repo.replaceAll(rec.connection(), List.of(numeric), "run-q3b");
        assertEquals("42", rec.batches.get(0).get(1), "values are stored as text");
        assertEquals("YES", rec.batches.get(0).get(9));
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

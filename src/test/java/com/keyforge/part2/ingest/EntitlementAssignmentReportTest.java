package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.KfAgentClient;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_entitlement_assignment (SQL report "Query 1"). Verifies Part 1's {@code NativeSqlReportRepository} contract: the 29
 * SELECT-alias text columns in order, identity row id, native_iiq_jdbc lineage, verbatim text / NULL values, and the
 * replace-all load (DELETE + batch INSERT in ONE transaction, rollback on failure), plus the service's fetch-everything-
 * first ordering (an HTTP failure never deletes the previous snapshot).
 */
class EntitlementAssignmentReportTest {

    /** Column order of iiq_native.kf_entitlement_assignment exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "id", "identity_id", "user_id", "user_name", "application_name", "account_name", "entitlement_attribute",
            "entitlement_value", "assigned", "granted_by_role", "entitlement_source", "entitlement_assignment_id",
            "entitlement_assigner", "entitlement_created", "entitlement_modified", "role_id", "role_name",
            "role_display_name", "role_type", "role_requestable", "identity_request_id", "access_request_id",
            "request_type", "requested_by", "request_date", "request_status", "request_operation",
            "provisioning_state", "assignment_type", "assignment_reason", "source_system", "source_interface",
            "extraction_run_id", "extracted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final SqlReportRepository repo = new SqlReportRepository("iiq_native_part2_test",
            EntitlementAssignmentReport.TABLE, EntitlementAssignmentReport.COLUMNS);

    @Test
    void ddlMatchesIiqNativeTextColumnsAndIdentityId() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY"));
        assertTrue(ddl.contains("assigned text") && ddl.contains("entitlement_created text"), "every report value is text");
        assertFalse(ddl.contains("record_hash") || ddl.contains("source_object_type") || ddl.contains("is_deleted"),
                "a SQL-report table has no hash, object type or soft delete");
        assertEquals(29, EntitlementAssignmentReport.COLUMNS.size());
    }

    @Test
    void replaceAllDeletesThenInsertsInOneTransactionWithVerbatimValues() throws Exception {
        TxStub tx = new TxStub(3, -1);
        JsonNode row = mapper.readTree("{\"identity_id\":\"7f00010198421229819849ebca230c82\",\"user_name\":\"Alexander Bailey – café ✓\","
                + "\"assigned\":\"1\",\"granted_by_role\":\"0\",\"entitlement_created\":\"2026-07-30 22:06:11.909\","
                + "\"role_id\":null,\"assignment_reason\":\"Requested\",\"unexpected_extra\":\"ignored\"}");
        JsonNode row2 = mapper.readTree("{\"identity_id\":\"x\",\"assigned\":1}");

        SqlReportRepository.Replaced r = repo.replaceAll(tx.connection(), List.of(row, row2), "run-89");

        assertEquals(new SqlReportRepository.Replaced(3, 2), r);
        assertEquals(List.of("autoCommit=false", "DELETE FROM iiq_native_part2_test.kf_entitlement_assignment",
                "addBatch", "addBatch", "executeBatch", "commit", "autoCommit=true"), tx.events);
        Map<Integer, Object> b = tx.batches.get(0);
        assertEquals("7f00010198421229819849ebca230c82", b.get(col("identity_id")));
        assertEquals("Alexander Bailey – café ✓", b.get(col("user_name")));
        assertEquals("1", b.get(col("assigned")), "values stay text");
        assertEquals("2026-07-30 22:06:11.909", b.get(col("entitlement_created")), "dates stay text verbatim");
        assertNull(b.get(col("role_id")), "JSON null -> SQL NULL");
        assertNull(b.get(col("request_type")), "absent column -> SQL NULL");
        assertEquals("IdentityIQ", b.get(30));
        assertEquals("native_iiq_jdbc", b.get(31), "Part 1 stamps the SQL-result path native_iiq_jdbc");
        assertEquals("run-89", b.get(32));
        assertEquals(32, b.size(), "only the 29 report columns + 3 lineage values; extra response fields are ignored");
        assertEquals("1", tx.batches.get(1).get(col("assigned")), "a numeric JSON value is stored as its text");
        assertFalse(repo.insertSql().contains("unexpected_extra"));
    }

    @Test
    void failureRollsBackSoThePreviousSnapshotSurvives() throws Exception {
        TxStub tx = new TxStub(5, 0); // executeBatch fails
        assertThrows(SQLException.class, () -> repo.replaceAll(tx.connection(),
                List.of(mapper.readTree("{\"identity_id\":\"a\"}")), "run-90"));
        assertTrue(tx.events.contains("rollback"));
        assertFalse(tx.events.contains("commit"));
    }

    @Test
    void serviceFetchesEveryPageBeforeTouchingTheDatabase() throws Exception {
        List<String> calls = new ArrayList<>();
        SqlReportImportService.PageFetcher pages = (resource, start, limit) -> {
            calls.add(resource + "?start=" + start + "&limit=" + limit);
            return start == 0 ? "[{\"identity_id\":\"a\"},{\"identity_id\":\"b\"}]" : "[{\"identity_id\":\"c\"}]";
        };
        TxStub tx = new TxStub(0, -1);
        SqlReportImportService service = new SqlReportImportService(pages, EntitlementAssignmentReport.RESOURCE, repo, 2);
        ImportResult result = service.run(tx.connection(), "EntitlementAssignment", "run-91");

        assertEquals(List.of("entitlement-assignment?start=0&limit=2", "entitlement-assignment?start=2&limit=2"), calls);
        assertEquals(3, result.fetched());
        assertEquals(3, result.inserted());
        assertEquals(3, tx.batches.size());

        SqlReportImportService.PageFetcher broken = (resource, start, limit) -> {
            if (start > 0) {
                throw new KfAgentClient.KfAgentHttpException("connection reset");
            }
            return "[{\"identity_id\":\"a\"},{\"identity_id\":\"b\"}]";
        };
        TxStub untouched = new TxStub(9, -1);
        assertThrows(KfAgentClient.KfAgentHttpException.class, () -> new SqlReportImportService(broken,
                EntitlementAssignmentReport.RESOURCE, repo, 2).run(untouched.connection(), "EntitlementAssignment", "run-92"));
        assertTrue(untouched.events.isEmpty(), "no DELETE when the fetch did not complete");
    }

    private static int col(String name) {
        return 1 + EntitlementAssignmentReport.COLUMNS.indexOf(name);
    }

    /** Records the transaction steps of replaceAll; failOnBatch = executeBatch call index that throws (-1 = never). */
    private static final class TxStub {
        final List<String> events = new ArrayList<>();
        final List<Map<Integer, Object>> batches = new ArrayList<>();
        private final int existingRows;
        private final int failOnBatch;
        private int batchCalls;

        TxStub(int existingRows, int failOnBatch) {
            this.existingRows = existingRows;
            this.failOnBatch = failOnBatch;
        }

        Connection connection() {
            boolean[] auto = {true};
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getAutoCommit" -> auto[0];
                        case "setAutoCommit" -> {
                            auto[0] = (Boolean) a[0];
                            events.add("autoCommit=" + a[0]);
                            yield null;
                        }
                        case "commit", "rollback" -> {
                            events.add(m.getName());
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
                        if (m.getName().equals("executeUpdate")) {
                            events.add((String) a[0]);
                            return existingRows;
                        }
                        if (m.getName().equals("execute")) {
                            return Boolean.FALSE; // DDL from ensureSchemaAndTable
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
                                events.add("addBatch");
                                batches.add(new LinkedHashMap<>(bound));
                                bound.clear();
                            }
                            case "executeBatch" -> {
                                if (batchCalls++ == failOnBatch) {
                                    throw new SQLException("simulated insert failure");
                                }
                                events.add("executeBatch");
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

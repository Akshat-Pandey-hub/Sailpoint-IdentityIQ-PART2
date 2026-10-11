package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.testsupport.RecordingJdbc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.testsupport.RecordingJdbc.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** kf_entitlement DDL/upsert contract and response→column binding, against recording JDBC stubs. */
class EntitlementRepositoryTest {

    /** Column order of iiq_native.kf_entitlement exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "entitlementid", "source_id", "name", "value", "display_name", "displayable_name", "attribute", "type",
            "ma_uuid", "reference_attribute", "purview", "application_id", "application_name", "instance",
            "native_identity", "requestable", "is_group", "is_permission", "uncorrelated", "aggregated",
            "iiq_elevated_access", "owner_id", "owner_name", "description", "descriptions", "permissions",
            "target_permissions", "inheritance", "associations", "attributes", "classifications",
            "classification_names", "classification_display_names", "member_attribute", "full_name",
            "is_group_type", "is_inactive", "is_auto_promotion", "is_differencable", "created_at", "modified_at",
            "last_refresh", "last_target_aggregation", "source_hash", "record_hash", "source_system",
            "source_interface", "source_object_type", "extraction_run_id", "extracted_at", "is_deleted",
            "deleted_at");

    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", EntitlementTable.SPEC);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ddlMatchesIiqNativeColumnOrderAndPk() {
        String ddl = repo.createTableSql();
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS iiq_native_part2_test.kf_entitlement ("));
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("entitlementid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("descriptions jsonb"));
        assertTrue(ddl.contains("is_differencable boolean"));
        assertTrue(ddl.contains("last_target_aggregation timestamp with time zone"));
        assertTrue(ddl.contains("record_hash text"));
        assertTrue(ddl.contains("is_deleted boolean NOT NULL DEFAULT false"));
    }

    @Test
    void upsertConflictsOnEntitlementidAndPlaceholdersLineUp() {
        String sql = repo.upsertSql();
        assertTrue(sql.startsWith("INSERT INTO iiq_native_part2_test.kf_entitlement (entitlementid, source_id,"));
        assertTrue(sql.contains("ON CONFLICT (entitlementid) DO UPDATE SET"));
        assertTrue(sql.contains("record_hash = EXCLUDED.record_hash"));
        assertTrue(sql.contains("is_deleted = false") && sql.contains("deleted_at = null")
                && sql.contains("extracted_at = now()"));
        assertFalse(sql.contains("userid"), "no Identity key leaks into Entitlement");
        assertFalse(sql.contains("entitlementid = EXCLUDED"), "pk is never rewritten");

        String cols = sql.substring(sql.indexOf('(') + 1, sql.indexOf(") VALUES"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(cols.split(",").length, vals.split(",").length);
        // pk + 43 business + record_hash + 4 lineage params = 49 '?'
        assertEquals(49, vals.chars().filter(ch -> ch == '?').count());
        assertEquals(IIQ_NATIVE_COLUMNS.size(), cols.split(",").length);
    }

    @Test
    void bindsKeyBusinessFieldsHashAndLineage() throws Exception {
        JsonNode row = mapper.readTree("{"
                + "\"source_id\":\"7f00010198421229819849f1427b0d58\","
                + "\"name\":null,"
                + "\"value\":\"00edc4ac-9a67-4e7e-abe3-f84d9e8ffee5\","
                + "\"application_name\":\"EntraTarget\","
                + "\"requestable\":true,"
                + "\"is_group\":false,"
                + "\"descriptions\":{},"
                + "\"permissions\":[],"
                + "\"attributes\":{\"sysDescriptions\":{\"en_US\":\"x\"}},"
                + "\"is_group_type\":\"true\","
                + "\"created_at\":\"2025-07-27T03:42:26.205Z\","
                + "\"modified_at\":null,"
                + "\"source_hash\":\"ea7e1e71aecc9cdb778d462b0e0618aad198a2d7\""
                + "}");
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), row, "run-42"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9842-1229-8198-49f1427b0d58", b.get(1), "entitlementid = canonical source_id");
        assertEquals("7f00010198421229819849f1427b0d58", b.get(col("source_id")), "source_id verbatim");
        assertSame(NULL, b.get(col("name")));
        assertEquals("EntraTarget", b.get(col("application_name")));
        assertEquals(Boolean.TRUE, b.get(col("requestable")));
        assertEquals(Boolean.FALSE, b.get(col("is_group")));
        assertEquals(Boolean.TRUE, b.get(col("is_group_type")), "textual boolean tolerated");
        assertSame(NULL, b.get(col("uncorrelated")), "absent boolean -> NULL");
        assertEquals("{}", b.get(col("descriptions")), "empty object kept, not nulled");
        assertEquals("[]", b.get(col("permissions")), "empty array kept, not nulled");
        assertEquals("{\"sysDescriptions\":{\"en_US\":\"x\"}}", b.get(col("attributes")));
        assertSame(NULL, b.get(col("classifications")), "absent jsonb -> NULL");
        assertEquals("2025-07-27T03:42:26.205Z", b.get(col("created_at")));
        assertSame(NULL, b.get(col("modified_at")));
        assertEquals("ea7e1e71aecc9cdb778d462b0e0618aad198a2d7", b.get(col("source_hash")), "IIQ hash kept as data");

        int hashIdx = 2 + EntitlementTable.BUSINESS.size();
        assertEquals(EntitlementTable.recordHash(row), b.get(hashIdx), "record_hash right after business cols");
        assertEquals(64, ((String) b.get(hashIdx)).length());
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.ManagedAttribute", b.get(hashIdx + 3));
        assertEquals("run-42", b.get(hashIdx + 4));
        assertEquals(hashIdx + 4, b.size(), "no extra parameters bound");
    }

    @Test
    void sameObjectAlwaysGetsTheSameKeyAcrossRuns() throws Exception {
        JsonNode row = mapper.readTree("{\"source_id\":\"7f00010198421229819849f1427b0d58\",\"value\":\"v\"}");
        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), row, "run-1");
        repo.upsert(jdbc.connection(), row, "run-2");
        assertEquals(jdbc.executions.get(0).get(1), jdbc.executions.get(1).get(1), "deterministic pk -> upsert, not dup");
        int hashIdx = 2 + EntitlementTable.BUSINESS.size();
        assertEquals(jdbc.executions.get(0).get(hashIdx), jdbc.executions.get(1).get(hashIdx), "stable record_hash");
    }

    private static int col(String name) {
        for (int i = 0; i < EntitlementTable.BUSINESS.size(); i++) {
            if (EntitlementTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

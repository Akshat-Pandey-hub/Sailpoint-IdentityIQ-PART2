package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keyforge.part2.testsupport.RecordingJdbc;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.testsupport.RecordingJdbc.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_application: Part 1 parity (golden fixture produced by Part 1's own NativeApplicationParser /
 * NativeApplicationRepository / NativeApplicationRestService.toJson), DDL/upsert contract, and binding.
 */
class ApplicationTableTest {

    /** Column order of iiq_native.kf_application exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "applicationid", "source_id", "name", "description", "type", "connector", "features_string",
            "profile_class", "proxied_name", "cluster", "icon", "aggregation_types", "before_provisioning_rule",
            "after_provisioning_rule", "account_schema_correlation_rule", "account_schema_customization_rule",
            "account_schema_creation_rule", "account_schema_refresh_rule", "application_creation_rule",
            "account_schema_correlation_rule_id", "account_schema_customization_rule_id",
            "account_schema_creation_rule_id", "account_schema_refresh_rule_id", "application_creation_rule_id",
            "score", "authoritative", "case_insensitive", "logical", "composite", "authentication_resource",
            "activity_enabled", "in_maintenance", "manages_other_apps", "native_change_detection_enabled",
            "supports_provisioning", "supports_account_only", "supports_additional_accounts",
            "supports_authenticate", "supports_group_provisioning", "supports_direct_permissions",
            "sync_provisioning", "owner_id", "owner_name", "secondary_owners", "remediators", "dependencies",
            "schemas", "descriptions", "attributes", "provisioning_config", "account_correlation_config",
            "manager_correlation_rule", "manager_correlation_filter", "service_account_filter",
            "rpa_account_filter", "disable_account_filter", "lock_account_filter", "created_at", "modified_at",
            "record_hash", "source_system", "source_interface", "source_object_type", "extraction_run_id",
            "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", ApplicationTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/application-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), hash, line.substring(3)));
            }
        }
        assertEquals(2, out.size(), "golden fixture rows");
        return out;
    }

    // ── parity with Part 1 ──

    @Test
    void recordHashAndApplicationIdMatchPart1() throws Exception {
        List<Golden> gs = golden();
        assertEquals("7f000101-9842-1229-8198-49f1427b0d58", gs.get(0).id(), "canonical form of source_id");
        for (Golden g : gs) {
            assertEquals(g.hash(), ApplicationTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), ApplicationTable.applicationId(g.rest()), "applicationid parity");
        }
    }

    @Test
    void everyBusinessFieldIsHashedIncludingScore() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : ApplicationTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 7);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), ApplicationTable.recordHash(changed), c.name + " must be part of record_hash");
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> responseKeys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(responseKeys::add);
        assertEquals(responseKeys, ApplicationTable.BUSINESS.stream().map(c -> c.name).toList());
    }

    // ── DDL / upsert contract ──

    @Test
    void ddlMatchesIiqNativeColumnOrderAndPk() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("applicationid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("score integer"));
        assertTrue(ddl.contains("schemas jsonb"));
        assertTrue(ddl.contains("provisioning_config text"));
    }

    @Test
    void upsertConflictsOnApplicationid() {
        String sql = repo.upsertSql();
        assertTrue(sql.startsWith("INSERT INTO iiq_native_part2_test.kf_application (applicationid, source_id,"));
        assertTrue(sql.contains("ON CONFLICT (applicationid) DO UPDATE SET"));
        assertFalse(sql.contains("applicationid = EXCLUDED"), "pk is never rewritten");
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        // pk + 58 business + record_hash + 4 lineage params
        assertEquals(64, vals.chars().filter(ch -> ch == '?').count());
        assertEquals("applications", ApplicationTable.SPEC.resource());
    }

    // ── binding ──

    @Test
    void bindsIntegerScoreJsonbNullsHashAndLineage() throws Exception {
        JsonNode row = golden().get(0).rest();
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), row, "run-7"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9842-1229-8198-49f1427b0d58", b.get(1));
        assertEquals("7f00010198421229819849f1427b0d58", b.get(col("source_id")));
        assertEquals(42, b.get(col("score")), "score bound as an int");
        assertEquals(Boolean.TRUE, b.get(col("authoritative")));
        assertSame(NULL, b.get(col("profile_class")));
        assertEquals("[]", b.get(col("remediators")), "empty array kept");
        assertEquals("{\"op\":\"eq\",\"v\":1}", b.get(col("service_account_filter")));
        assertSame(NULL, b.get(col("rpa_account_filter")));
        assertEquals("<ProvisioningConfig/>", b.get(col("provisioning_config")), "config stays text");
        assertEquals("2026-09-30T14:44:40Z", b.get(col("modified_at")));

        int hashIdx = 2 + ApplicationTable.BUSINESS.size();
        assertEquals(golden().get(0).hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.Application", b.get(hashIdx + 3));
        assertEquals("run-7", b.get(hashIdx + 4));
        assertEquals(hashIdx + 4, b.size());
    }

    @Test
    void nullScoreIsSqlNullAndNonNumericScoreFailsTheRow() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), golden().get(1).rest(), "run-7");
        assertSame(NULL, jdbc.executions.get(0).get(col("score")));

        ObjectNode bad = golden().get(0).rest().deepCopy();
        bad.put("score", "high");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> repo.upsert(new RecordingJdbc().connection(), bad, "run-7"));
        assertTrue(e.getMessage().contains("score"), e.getMessage());
    }

    private static int col(String name) {
        for (int i = 0; i < ApplicationTable.BUSINESS.size(); i++) {
            if (ApplicationTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

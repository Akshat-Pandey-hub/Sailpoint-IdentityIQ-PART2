package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keyforge.part2.id.CanonicalIds;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_role: Part 1 parity (golden fixture produced by Part 1's own NativeRoleParser / NativeRoleRepository /
 * NativeRoleRestService.toJson — incl. null and non-GUID source_id fallbacks), the trailing-column DDL layout
 * of iiq_native.kf_role, text-vs-jsonb fields, integer risk_score_weight, and binding.
 */
class RoleTableTest {

    /** Column order of iiq_native.kf_role exactly as in iiq_native.sql (6 columns after deleted_at). */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "roleid", "source_id", "name", "display_name", "displayable_name", "full_name", "description", "type",
            "assignment_id", "activity_enabled", "allow_duplicate_accounts", "allow_multiple_assignments",
            "auto_promotion", "differencable", "iiq_elevated_access", "merge_templates", "or_profiles",
            "pending_delete", "has_selector", "risk_score_weight", "owner_id", "owner_name", "activation_date",
            "deactivation_date", "descriptions", "attributes", "created_at", "modified_at", "record_hash",
            "source_system", "source_interface", "source_object_type", "extraction_run_id", "extracted_at",
            "is_deleted", "deleted_at", "role_type_definition", "applications", "monitored_applications",
            "scorecard", "selector_summary", "selector");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", RoleTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/role-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), hash, line.substring(3)));
            }
        }
        assertEquals(3, out.size(), "golden fixture rows");
        return out;
    }

    // ── parity with Part 1 ──

    @Test
    void recordHashAndRoleIdMatchPart1IncludingFallbacks() throws Exception {
        List<Golden> gs = golden();
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3452bb", gs.get(0).id(), "canonical form of source_id");
        for (Golden g : gs) {
            assertEquals(g.hash(), RoleTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), RoleTable.roleId(g.rest()), "roleid parity for " + g.rest());
        }
        assertEquals(CanonicalIds.deterministicUuid("native-role|No Id Role"), gs.get(1).id(), "null id -> name");
        assertEquals(CanonicalIds.deterministicUuid("native-role|not-a-guid"), gs.get(2).id(), "non-GUID id -> id");
    }

    @Test
    void everyResponseFieldIncludingTrailingIsHashed() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : RoleTable.SPEC.allBusiness()) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 7);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), RoleTable.recordHash(changed), c.name + " must be part of record_hash");
        }
    }

    @Test
    void responseFieldsAreBusinessThenTrailing() throws Exception {
        List<String> responseKeys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(responseKeys::add);
        assertEquals(responseKeys, RoleTable.SPEC.allBusiness().stream().map(c -> c.name).toList());
        assertEquals(33, responseKeys.size());
    }

    // ── DDL / upsert contract ──

    @Test
    void ddlMatchesIiqNativeColumnOrderIncludingTrailingColumns() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("roleid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("risk_score_weight integer"));
        assertTrue(ddl.contains("applications text"), "applications is text in iiq_native, not jsonb");
        assertTrue(ddl.contains("selector jsonb"));
    }

    @Test
    void upsertConflictsOnRoleidAndUpdatesTrailingColumns() {
        String sql = repo.upsertSql();
        assertTrue(sql.startsWith("INSERT INTO iiq_native_part2_test.kf_role (roleid, source_id,"));
        assertTrue(sql.contains("ON CONFLICT (roleid) DO UPDATE SET"));
        assertTrue(sql.contains("selector = EXCLUDED.selector"), "trailing columns are updated too");
        assertTrue(sql.contains("selector_summary = EXCLUDED.selector_summary"));
        assertFalse(sql.contains("roleid = EXCLUDED"), "pk is never rewritten");
        String cols = sql.substring(sql.indexOf('(') + 1, sql.indexOf(") VALUES"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(cols.split(",").length, vals.split(",").length);
        // pk + 33 response fields + record_hash + 4 lineage params
        assertEquals(39, vals.chars().filter(ch -> ch == '?').count());
        assertEquals("roles", RoleTable.SPEC.resource());
    }

    // ── binding ──

    @Test
    void bindsTextLookingJsonAsTextJsonbIntegerHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-5"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3452bb", b.get(1));
        assertEquals("7f0001019f061fdc819f34b0ac3452bb", b.get(col("source_id")), "source_id verbatim");
        assertEquals(250, b.get(col("risk_score_weight")), "integer");
        assertEquals(Boolean.TRUE, b.get(col("allow_multiple_assignments")));
        assertSame(NULL, b.get(col("assignment_id")));
        assertSame(NULL, b.get(col("activation_date")));
        assertEquals("2027-01-01T00:00:00Z", b.get(col("deactivation_date")));
        assertEquals("[\"EntraTarget\",\"HR DB\"]", b.get(col("applications")), "text column keeps the raw string");
        assertEquals("", b.get(col("monitored_applications")), "empty string kept, not nulled");
        assertSame(NULL, b.get(col("scorecard")));
        assertEquals("{\"filter\":\"department == \\\"Finance\\\"\",\"type\":\"IdentitySelector\"}",
                b.get(col("selector")), "selector is jsonb");

        int hashIdx = 2 + RoleTable.SPEC.allBusiness().size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.Bundle", b.get(hashIdx + 3));
        assertEquals("run-5", b.get(hashIdx + 4));
        assertEquals(hashIdx + 4, b.size());
    }

    @Test
    void nullsAndZeroBindCorrectly() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), golden().get(1).rest(), "run-5");
        repo.upsert(jdbc.connection(), golden().get(2).rest(), "run-5");
        assertSame(NULL, jdbc.executions.get(0).get(col("risk_score_weight")), "null integer -> SQL NULL");
        assertSame(NULL, jdbc.executions.get(0).get(col("selector")), "null jsonb -> SQL NULL");
        assertEquals("{}", jdbc.executions.get(0).get(col("attributes")));
        assertEquals(0, jdbc.executions.get(1).get(col("risk_score_weight")), "0 is a value, not NULL");
    }

    /** Parameter index of a response column (pk is 1; columns follow in business+trailing order). */
    private static int col(String name) {
        List<ColumnSpec> all = RoleTable.SPEC.allBusiness();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

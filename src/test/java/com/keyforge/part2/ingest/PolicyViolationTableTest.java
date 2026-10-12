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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_violation. Live policy violations are empty and iiq_native.sql holds no kf_violation rows, so validation is against
 * a golden fixture produced by Part 1's own NativeViolationParser / Repository / REST toJson. Row 1 references the REAL
 * live policy ("Account Template") and constraint ("Multiple Application Accounts") ids. Covers key + fallbacks, the
 * all-field hash, text-vs-jsonb fields, the policy / constraint links, DDL and binding.
 */
class PolicyViolationTableTest {

    private static final String POLICY = "7f000101971416688197147685f80119";
    private static final String CONSTRAINT = "7f000101971416688197147685f8011a";

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "violationid", "source_id", "name", "identity_id", "identity_name", "policy_id", "policy_name",
            "constraint_id", "constraint_name", "status", "active", "left_bundles", "right_bundles",
            "entitlements_marked_for_remediation", "bundles_marked_for_remediation", "relevant_apps",
            "violating_entitlements", "arguments", "created_at", "modified_at", "record_hash", "source_system",
            "source_interface", "source_object_type", "extraction_run_id", "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", PolicyViolationTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/policy-violation-part1-golden.txt");
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

    @Test
    void keyAndHashMatchPart1IncludingFallbacks() throws Exception {
        List<Golden> gs = golden();
        for (Golden g : gs) {
            assertEquals(g.hash(), PolicyViolationTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), PolicyViolationTable.violationId(g.rest()), "key parity");
        }
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac34a1bb", gs.get(0).id());
        assertEquals(CanonicalIds.deterministicUuid("native-violation|No Id Violation"), gs.get(1).id(), "null id -> name");
        assertEquals(CanonicalIds.deterministicUuid("native-violation|not-a-guid"), gs.get(2).id(), "non-GUID id -> id");
    }

    @Test
    void linksToItsPolicyAndConstraintByRawIdsAndKeyIsItsOwn() throws Exception {
        JsonNode v = golden().get(0).rest();
        assertEquals(POLICY, v.get("policy_id").asText());
        assertEquals(CONSTRAINT, v.get("constraint_id").asText());
        String key = PolicyViolationTable.violationId(v);
        assertNotEquals(CanonicalIds.canonicalUuid(POLICY), key);
        assertNotEquals(CanonicalIds.canonicalUuid(CONSTRAINT), key);
        ObjectNode moved = v.deepCopy();
        moved.put("policy_id", "x").put("constraint_id", "y");
        assertEquals(key, PolicyViolationTable.violationId(moved), "key does not depend on the links");
    }

    @Test
    void everyFieldIsHashed() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : PolicyViolationTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), PolicyViolationTable.recordHash(changed), c.name + " must be hashed");
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(keys, PolicyViolationTable.BUSINESS.stream().map(c -> c.name).toList());
        assertEquals(19, keys.size());
    }

    @Test
    void ddlAndUpsertMatchIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("violationid uuid PRIMARY KEY") && ddl.contains("relevant_apps jsonb"));
        assertTrue(ddl.contains("left_bundles text") && ddl.contains("bundles_marked_for_remediation text"));
        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (violationid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(25, vals.chars().filter(ch -> ch == '?').count(), "pk + 19 + record_hash + 4 lineage");
    }

    @Test
    void bindsLinksTextRoleSetsJsonbHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-83"));
        Map<Integer, Object> b = jdbc.executions.get(0);
        assertEquals(g.id(), b.get(1));
        assertEquals(POLICY, b.get(col("policy_id")));
        assertEquals(CONSTRAINT, b.get(col("constraint_id")));
        assertEquals("[\"Finance Analyst\"]", b.get(col("left_bundles")), "JSON-looking role set stays text verbatim");
        assertEquals("Payroll Admin,HR Admin", b.get(col("right_bundles")));
        assertEquals("[\"EntraTarget\",{\"n\":1.5}]", b.get(col("relevant_apps")));
        assertEquals("[]", b.get(col("violating_entitlements")), "empty array kept");
        assertEquals(Boolean.TRUE, b.get(col("active")));
        assertSame(NULL, b.get(col("entitlements_marked_for_remediation")));
        int hashIdx = 2 + PolicyViolationTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.PolicyViolation", b.get(hashIdx + 3));
        assertEquals("run-83", b.get(hashIdx + 4));

        RecordingJdbc sparse = new RecordingJdbc();
        repo.upsert(sparse.connection(), golden().get(1).rest(), "run-83");
        assertSame(NULL, sparse.executions.get(0).get(col("active")));
        assertSame(NULL, sparse.executions.get(0).get(col("relevant_apps")));
    }

    private static int col(String name) {
        for (int i = 0; i < PolicyViolationTable.BUSINESS.size(); i++) {
            if (PolicyViolationTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

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
import java.util.Set;

import static com.keyforge.part2.testsupport.RecordingJdbc.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_policy_constraint. Golden fixture produced by Part 1's own NativePolicyConstraintParser / Repository / REST toJson.
 * Covers key + the composite fallback, the 16-field hash, the parent policy link, jsonb / integers, DDL, binding.
 */
class PolicyConstraintTableTest {

    private static final String POLICY = "7f000101971416688197147685e90115";

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "policyconstraintid", "source_id", "policy_id", "policy_name", "name", "description", "constraint_type",
            "weight", "compensating_control", "violation_owner_id", "violation_owner_name", "violation_owner_type",
            "left_bundles", "right_bundles", "selectors", "selector_count", "arguments", "created_at", "modified_at",
            "record_hash", "source_system", "source_interface", "source_object_type", "extraction_run_id",
            "extracted_at", "is_deleted", "deleted_at");

    private static final Set<String> NOT_HASHED = Set.of("policy_name", "violation_owner_name");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", PolicyConstraintTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/policy-constraint-part1-golden.txt");
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
    void keyAndHashMatchPart1IncludingTheCompositeFallback() throws Exception {
        List<Golden> gs = golden();
        for (Golden g : gs) {
            assertEquals(g.hash(), PolicyConstraintTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), PolicyConstraintTable.policyConstraintId(g.rest()), "key parity");
        }
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3491aa", gs.get(0).id());
        assertEquals(CanonicalIds.deterministicUuid("native-policy-constraint|" + POLICY + "|No Id Rule|GenericConstraint"),
                gs.get(1).id(), "null id -> policy|name|type");
        assertEquals(CanonicalIds.deterministicUuid("native-policy-constraint|null|not-a-guid|null"), gs.get(2).id(),
                "non-GUID id -> policy|id|type, nulls as text");
    }

    @Test
    void constraintKeyIsItsOwnIdAndLinksToItsPolicy() throws Exception {
        JsonNode c = golden().get(0).rest();
        assertEquals(POLICY, c.get("policy_id").asText(), "parent = the Policy's raw IIQ id");
        assertNotEquals(CanonicalIds.canonicalUuid(POLICY), PolicyConstraintTable.policyConstraintId(c), "not the policy id");
        ObjectNode moved = c.deepCopy();
        moved.put("policy_id", "7f000101aaaaaaaaaaaaaaaaaaaaaaaa").put("policy_name", "Other");
        assertEquals(PolicyConstraintTable.policyConstraintId(c), PolicyConstraintTable.policyConstraintId(moved),
                "a constraint with its own id keeps its key");
    }

    @Test
    void hashCoversAllButPolicyNameAndViolationOwnerName() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec col : PolicyConstraintTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (col.kind) {
                case INTEGER -> changed.put(col.name, 7);
                case TIMESTAMPTZ -> changed.put(col.name, "2001-01-01T00:00:00Z");
                default -> changed.put(col.name, "changed-" + col.name);
            }
            if (NOT_HASHED.contains(col.name)) {
                assertEquals(g.hash(), PolicyConstraintTable.recordHash(changed), col.name + " is NOT hashed in Part 1");
            } else {
                assertNotEquals(g.hash(), PolicyConstraintTable.recordHash(changed), col.name + " must be hashed");
            }
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(keys, PolicyConstraintTable.BUSINESS.stream().map(c -> c.name).toList());
        assertEquals(18, keys.size());
    }

    @Test
    void ddlAndUpsertMatchIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("policyconstraintid uuid PRIMARY KEY") && ddl.contains("left_bundles jsonb"));
        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (policyconstraintid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(24, vals.chars().filter(ch -> ch == '?').count(), "pk + 18 + record_hash + 4 lineage");
    }

    @Test
    void bindsParentJsonbIntegersHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-79"));
        Map<Integer, Object> b = jdbc.executions.get(0);
        assertEquals(g.id(), b.get(1));
        assertEquals(POLICY, b.get(col("policy_id")));
        assertEquals(1000, b.get(col("weight")));
        assertEquals(0, b.get(col("selector_count")), "0 is a value");
        assertEquals("[]", b.get(col("selectors")), "empty array kept");
        assertEquals("[{\"id\":\"r2\",\"name\":\"Payroll Admin\",\"n\":1.5}]", b.get(col("right_bundles")));
        assertSame(NULL, b.get(col("violation_owner_id")));
        int hashIdx = 2 + PolicyConstraintTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.BaseConstraint", b.get(hashIdx + 3));
        assertEquals("run-79", b.get(hashIdx + 4));

        RecordingJdbc sparse = new RecordingJdbc();
        repo.upsert(sparse.connection(), golden().get(1).rest(), "run-79");
        assertSame(NULL, sparse.executions.get(0).get(col("weight")));
        assertSame(NULL, sparse.executions.get(0).get(col("selectors")));
    }

    private static int col(String name) {
        for (int i = 0; i < PolicyConstraintTable.BUSINESS.size(); i++) {
            if (PolicyConstraintTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

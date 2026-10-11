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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.keyforge.part2.testsupport.RecordingJdbc.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_policy. Golden fixture produced by Part 1's own NativePolicyParser / NativePolicyRepository / REST toJson.
 * Covers key + fallbacks, the all-21-field hash, text-vs-jsonb policy fields, the trailing-column DDL, binding.
 */
class PolicyTableTest {

    /** Column order of iiq_native.kf_policy exactly as in iiq_native.sql (9 columns after deleted_at). */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "policyid", "source_id", "name", "type", "type_key", "description", "descriptions", "executor",
            "violation_owner_id", "violation_owner_name", "constraint_count", "created_at", "modified_at", "record_hash",
            "source_system", "source_interface", "source_object_type", "extraction_run_id", "extracted_at", "is_deleted",
            "deleted_at", "state", "violation_rule", "violation_workflow", "signature", "certification_actions",
            "violation_owner_type", "violation_owner_rule_id", "violation_owner_rule_name", "is_template");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", PolicyTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/policy-part1-golden.txt");
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
            assertEquals(g.hash(), PolicyTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), PolicyTable.policyId(g.rest()), "policyid parity for " + g.rest());
        }
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3459aa", gs.get(0).id());
        assertEquals(CanonicalIds.deterministicUuid("native-policy|No Id Policy"), gs.get(1).id(), "null id -> name");
        assertEquals(CanonicalIds.deterministicUuid("native-policy|not-a-guid"), gs.get(2).id(), "non-GUID id -> id");
    }

    /**
     * Real data. The live "SOD Template" row hashes differently from the row stored in iiq_native.sql, ONLY because
     * that row was written before Part 1's mapper filled violation_owner_type / violation_owner_rule_id /
     * violation_owner_rule_name / is_template (they are NULL in the dump). With those 4 nulled, Part 2's 21-field hash
     * reproduces the stored hash exactly, proving the hash rule is identical to Part 1's.
     */
    @Test
    void storedPart1HashIsReproducedOnceTheFourLaterFieldsAreAsTheyWereStored() throws Exception {
        JsonNode live = mapper.readTree("{\"source_id\":\"7f000101971416688197147685e90115\",\"name\":\"SOD Template\","
                + "\"type\":\"SOD\",\"type_key\":\"policy_type_sod\",\"description\":null,\"descriptions\":{},"
                + "\"executor\":\"sailpoint.policy.SODPolicyExecutor\",\"violation_owner_id\":null,"
                + "\"violation_owner_name\":null,\"violation_owner_type\":\"Manager\",\"violation_owner_rule_id\":null,"
                + "\"violation_owner_rule_name\":null,\"is_template\":true,\"constraint_count\":0,"
                + "\"created_at\":\"2025-05-28T01:16:41.321Z\",\"modified_at\":\"2025-05-28T01:17:12.606Z\","
                + "\"state\":\"Inactive\",\"violation_rule\":null,\"violation_workflow\":null,\"signature\":null,"
                + "\"certification_actions\":\"Remediated,Mitigated,Delegated\"}");
        String storedInIiqNative = "ed842dacb02da29bb44cda1172baffe4339b2308c83e88ba38a04bc680749678";

        assertEquals("7f000101-9714-1668-8197-147685e90115", PolicyTable.policyId(live), "key equals the stored policyid");
        assertNotEquals(storedInIiqNative, PolicyTable.recordHash(live), "current values differ from the stored ones");

        ObjectNode asStored = live.deepCopy();
        for (String f : List.of("violation_owner_type", "violation_owner_rule_id", "violation_owner_rule_name", "is_template")) {
            asStored.putNull(f);
        }
        assertEquals(storedInIiqNative, PolicyTable.recordHash(asStored));
    }

    @Test
    void everyFieldIncludingTrailingIsHashed() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : PolicyTable.SPEC.allBusiness()) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 9);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), PolicyTable.recordHash(changed), c.name + " must be hashed");
        }
    }

    @Test
    void responseFieldsAreExactlyTheMappedColumns() throws Exception {
        Set<String> keys = new HashSet<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        Set<String> mapped = new HashSet<>();
        PolicyTable.SPEC.allBusiness().forEach(c -> mapped.add(c.name));
        assertEquals(keys, mapped);
        assertEquals(21, mapped.size());
    }

    @Test
    void ddlMatchesIiqNativeIncludingTrailingColumns() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("policyid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("descriptions jsonb") && ddl.contains("constraint_count integer"));
        assertTrue(ddl.contains("violation_rule text") && ddl.contains("certification_actions text"),
                "structured-looking policy fields are text in iiq_native");
        assertTrue(ddl.contains("is_template boolean"));

        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (policyid) DO UPDATE SET"));
        assertTrue(sql.contains("is_template = EXCLUDED.is_template"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(27, vals.chars().filter(ch -> ch == '?').count(), "pk + 21 + record_hash + 4 lineage");
        assertEquals("policies", PolicyTable.SPEC.resource());
    }

    @Test
    void bindsPolicyFieldsHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-41"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3459aa", b.get(1));
        assertEquals("7f0001019f061fdc819f34b0ac3459aa", b.get(col("source_id")), "source_id verbatim");
        assertEquals("{\"en_US\":\"Separation of duties\",\"n\":1.5}", b.get(col("descriptions")));
        assertEquals(2, b.get(col("constraint_count")));
        assertEquals("Mitigated,Remediated,Delegated", b.get(col("certification_actions")), "comma-joined text");
        assertEquals("<Signature/>", b.get(col("signature")));
        assertEquals("Identity", b.get(col("violation_owner_type")));
        assertSame(NULL, b.get(col("violation_owner_rule_id")));
        assertEquals(Boolean.FALSE, b.get(col("is_template")));

        int hashIdx = 2 + PolicyTable.SPEC.allBusiness().size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.Policy", b.get(hashIdx + 3));
        assertEquals("run-41", b.get(hashIdx + 4));

        RecordingJdbc sparse = new RecordingJdbc();
        repo.upsert(sparse.connection(), golden().get(1).rest(), "run-41");
        repo.upsert(sparse.connection(), golden().get(2).rest(), "run-41");
        assertEquals(0, sparse.executions.get(0).get(col("constraint_count")), "0 is a value");
        assertSame(NULL, sparse.executions.get(0).get(col("descriptions")));
        assertEquals(Boolean.TRUE, sparse.executions.get(0).get(col("is_template")));
        assertSame(NULL, sparse.executions.get(1).get(col("constraint_count")));
        assertEquals("{}", sparse.executions.get(1).get(col("descriptions")), "empty object kept");
    }

    private static int col(String name) {
        List<ColumnSpec> all = PolicyTable.SPEC.allBusiness();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

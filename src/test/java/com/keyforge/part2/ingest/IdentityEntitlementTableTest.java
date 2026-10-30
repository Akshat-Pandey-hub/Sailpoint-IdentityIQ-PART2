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
 * kf_identity_entitlement: Part 1 parity (golden fixture produced by Part 1's own
 * NativeIdentityEntitlementParser / Repository / RestService.toJson — canonical id, null source_id composite
 * fallback, non-GUID source_id with an all-null natural key), the 30-field hash subset, DDL and binding.
 */
class IdentityEntitlementTableTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "entitlementid", "source_id", "identity_id", "identity_name", "application_id", "application_name",
            "native_identity", "instance", "attribute_name", "attribute_value", "value_list", "type", "display_name",
            "annotation", "assigned", "granted_by_role", "allowed", "connected", "aggregation_state", "source",
            "source_object", "assigner", "assignment_id", "assignment_note", "source_assignable_roles",
            "source_detected_roles", "certification_item_id", "pending_certification_item_id", "request_item_id",
            "pending_request_item_id", "start_date", "end_date", "created_at", "modified_at", "record_hash",
            "source_system", "source_interface", "source_object_type", "extraction_run_id", "extracted_at",
            "is_deleted", "deleted_at");

    /** Response fields Part 1 leaves OUT of record_hash. */
    private static final Set<String> NOT_HASHED = Set.of("identity_name", "application_id", "display_name");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo =
            new EntityRepository("iiq_native_part2_test", IdentityEntitlementTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/identity-entitlement-part1-golden.txt");
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

    // ── key ──

    @Test
    void keyAndRecordHashMatchPart1() throws Exception {
        List<Golden> gs = golden();
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3453dd", gs.get(0).id(), "canonical IdentityEntitlement id");
        for (Golden g : gs) {
            assertEquals(g.hash(), IdentityEntitlementTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), IdentityEntitlementTable.identityEntitlementId(g.rest()), "key parity for " + g.rest());
        }
    }

    @Test
    void fallbackIsTheCompositeNaturalKeyNotTheSourceId() throws Exception {
        List<Golden> gs = golden();
        assertEquals(CanonicalIds.deterministicUuid("native-identity-entitlement|7f00010198421229819849ebca230c82"
                + "|HR DB|jdoe|role|admin"), gs.get(1).id(), "null source_id -> natural key");
        // non-GUID source_id is NOT used in the seed — the natural key is (here all null -> text "null")
        assertEquals(CanonicalIds.deterministicUuid("native-identity-entitlement|null|null|null|null|null"),
                gs.get(2).id());
        assertNotEquals(CanonicalIds.canonicalUuid("7f00010198421229819849ebca230c82"), gs.get(0).id(),
                "key is not the identity id");
    }

    // ── hash subset ──

    @Test
    void hashCoversAllButIdentityNameApplicationIdAndDisplayName() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : IdentityEntitlementTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            if (NOT_HASHED.contains(c.name)) {
                assertEquals(g.hash(), IdentityEntitlementTable.recordHash(changed), c.name + " is NOT hashed in Part 1");
            } else {
                assertNotEquals(g.hash(), IdentityEntitlementTable.recordHash(changed), c.name + " must be hashed");
            }
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> responseKeys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(responseKeys::add);
        assertEquals(responseKeys, IdentityEntitlementTable.BUSINESS.stream().map(c -> c.name).toList());
    }

    // ── DDL / upsert / binding ──

    @Test
    void ddlAndUpsertMatchIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("entitlementid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("value_list jsonb"));
        assertTrue(ddl.contains("source_assignable_roles text"));

        String sql = repo.upsertSql();
        assertTrue(sql.startsWith("INSERT INTO iiq_native_part2_test.kf_identity_entitlement AS cur (entitlementid, source_id,"));
        assertTrue(sql.contains("ON CONFLICT (entitlementid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(39, vals.chars().filter(ch -> ch == '?').count(), "pk + 33 + record_hash + 4 lineage");
        assertEquals("identity-entitlements", IdentityEntitlementTable.SPEC.resource());
    }

    @Test
    void bindsKeyFieldsHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-13"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3453dd", b.get(1));
        assertEquals("7f0001019f061fdc819f34b0ac3453dd", b.get(col("source_id")), "source_id verbatim");
        assertEquals("7f00010198421229819849ebca230c82", b.get(col("identity_id")), "raw IIQ identity id");
        assertEquals("[\"00edc4ac-9a67-4e7e-abe3-f84d9e8ffee5\",{\"n\":1.5}]", b.get(col("value_list")));
        assertEquals(Boolean.TRUE, b.get(col("assigned")));
        assertEquals("", b.get(col("source_detected_roles")), "empty string kept");
        assertSame(NULL, b.get(col("annotation")));
        assertSame(NULL, b.get(col("start_date")));

        int hashIdx = 2 + IdentityEntitlementTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.IdentityEntitlement", b.get(hashIdx + 3));
        assertEquals("run-13", b.get(hashIdx + 4));
        assertEquals(hashIdx + 4, b.size());

        RecordingJdbc fallback = new RecordingJdbc();
        repo.upsert(fallback.connection(), golden().get(1).rest(), "run-13");
        assertEquals(golden().get(1).id(), fallback.executions.get(0).get(1));
        assertSame(NULL, fallback.executions.get(0).get(col("value_list")));
        assertSame(NULL, fallback.executions.get(0).get(col("assigned")));
    }

    private static int col(String name) {
        for (int i = 0; i < IdentityEntitlementTable.BUSINESS.size(); i++) {
            if (IdentityEntitlementTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

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
 * kf_identity_role: Part 1 parity (golden fixture produced by Part 1's own NativeIdentityRoleParser /
 * NativeIdentityRoleRepository / NativeIdentityRoleRestService.toJson — ASSIGNED, DETECTED with null
 * assignment_id, and an all-null key), the composite edge key, the 18-field hash subset, DDL and binding.
 */
class IdentityRoleTableTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "identityroleid", "identity_id", "identity_name", "role_id", "role_name", "relationship_type",
            "assignment_id", "assigner", "assigned_date", "start_date", "end_date", "source", "negative", "manual",
            "detection_assignment_ids", "comments", "future_assignment", "promoted_soft_permit", "detection_date",
            "targets", "record_hash", "source_system", "source_interface", "source_object_type",
            "extraction_run_id", "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", IdentityRoleTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/identity-role-part1-golden.txt");
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

    // ── key: composite, name-based, never the identity or role id ──

    @Test
    void edgeKeyAndRecordHashMatchPart1() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.hash(), IdentityRoleTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), IdentityRoleTable.identityRoleId(g.rest()), "identityroleid parity for " + g.rest());
        }
    }

    @Test
    void edgeKeyIsCompositeNotTheIdentityOrRoleId() throws Exception {
        JsonNode assigned = golden().get(0).rest();
        String key = IdentityRoleTable.identityRoleId(assigned);
        assertEquals(CanonicalIds.deterministicUuid("native-identity-role|7f00010198421229819849ebca230c82"
                + "|7f0001019f061fdc819f34b0ac3452bb|ASSIGNED|a1b2c3d4e5f6"), key);
        assertNotEquals(CanonicalIds.canonicalUuid("7f00010198421229819849ebca230c82"), key, "not the identity id");
        assertNotEquals(CanonicalIds.canonicalUuid("7f0001019f061fdc819f34b0ac3452bb"), key, "not the role id");

        // same identity+role, other relationship type -> a different edge
        ObjectNode detected = assigned.deepCopy();
        detected.put("relationship_type", "DETECTED");
        assertNotEquals(key, IdentityRoleTable.identityRoleId(detected));
        // other assignment of the same role -> a different edge
        ObjectNode other = assigned.deepCopy();
        other.put("assignment_id", "zzz");
        assertNotEquals(key, IdentityRoleTable.identityRoleId(other));
        // non-key fields don't move the key
        ObjectNode renamed = assigned.deepCopy();
        renamed.put("role_name", "Renamed").put("identity_name", "x").put("comments", "y");
        assertEquals(key, IdentityRoleTable.identityRoleId(renamed));
    }

    @Test
    void nullKeyPartsAreConcatenatedAsTextNullLikePart1() throws Exception {
        List<Golden> gs = golden();
        assertEquals(CanonicalIds.deterministicUuid("native-identity-role|7f00010198421229819849ebca230c82"
                + "|7f0001019f061fdc819f34b0ac3452cc|DETECTED|null"), gs.get(1).id(), "null assignment_id");
        assertEquals(CanonicalIds.deterministicUuid("native-identity-role|null|null|null|null"), gs.get(2).id());
    }

    // ── hash subset ──

    @Test
    void hashCoversEveryFieldExceptIdentityName() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : IdentityRoleTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            if ("identity_name".equals(c.name)) {
                assertEquals(g.hash(), IdentityRoleTable.recordHash(changed), "identity_name is NOT hashed in Part 1");
            } else {
                assertNotEquals(g.hash(), IdentityRoleTable.recordHash(changed), c.name + " must be hashed");
            }
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> responseKeys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(responseKeys::add);
        assertEquals(responseKeys, IdentityRoleTable.BUSINESS.stream().map(c -> c.name).toList());
        assertFalse(responseKeys.contains("source_id"), "an edge has no source_id");
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
        assertTrue(ddl.contains("identityroleid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("detection_assignment_ids text"));
        assertTrue(ddl.contains("targets jsonb"));

        String sql = repo.upsertSql();
        assertTrue(sql.startsWith("INSERT INTO iiq_native_part2_test.kf_identity_role (identityroleid, identity_id,"));
        assertTrue(sql.contains("ON CONFLICT (identityroleid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(25, vals.chars().filter(ch -> ch == '?').count(), "pk + 19 + record_hash + 4 lineage");
        assertEquals("identity-roles", IdentityRoleTable.SPEC.resource());
    }

    @Test
    void bindsEdgeKeyFieldsHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-11"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals(g.id(), b.get(1));
        assertEquals("7f00010198421229819849ebca230c82", b.get(col("identity_id")), "raw IIQ id kept");
        assertEquals("7f0001019f061fdc819f34b0ac3452bb", b.get(col("role_id")), "raw IIQ id kept");
        assertEquals("ASSIGNED", b.get(col("relationship_type")));
        assertEquals(Boolean.TRUE, b.get(col("manual")));
        assertSame(NULL, b.get(col("start_date")));
        assertSame(NULL, b.get(col("detection_assignment_ids")));
        assertTrue(((String) b.get(col("targets"))).startsWith("[{\"application\":\"EntraTarget\""));

        int hashIdx = 2 + IdentityRoleTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.Identity.roles", b.get(hashIdx + 3));
        assertEquals("run-11", b.get(hashIdx + 4));
        assertEquals(hashIdx + 4, b.size());

        RecordingJdbc detected = new RecordingJdbc();
        repo.upsert(detected.connection(), golden().get(1).rest(), "run-11");
        assertEquals("[]", detected.executions.get(0).get(col("targets")), "empty array kept");
        assertEquals("a1b2c3d4e5f6,ffff", detected.executions.get(0).get(col("detection_assignment_ids")));
    }

    private static int col(String name) {
        for (int i = 0; i < IdentityRoleTable.BUSINESS.size(); i++) {
            if (IdentityRoleTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

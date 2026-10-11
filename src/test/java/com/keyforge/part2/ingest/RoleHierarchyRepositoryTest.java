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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_role_hierarchy. The golden fixture was produced by Part 1's own code (NativeRoleRelationshipParser,
 * NativeRoleHierarchyRepository.id/hash, NativeRoleHierarchyRestService.toJson, the mapper's natural-key expression).
 * Its FIRST row is the real edge Part 1 persisted in iiq_native.sql (pg_restore of kf_role_hierarchy).
 */
class RoleHierarchyRepositoryTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "rolehierarchyid", "source_role_id", "role_id", "role_name", "related_role_source_id", "related_role_id",
            "related_role_name", "relationship_type", "source_system", "source_interface", "source_object_type",
            "src_object_id", "src_natural_key", "extraction_run_id", "extracted_at", "record_hash", "is_deleted",
            "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final RoleHierarchyRepository repo = new RoleHierarchyRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String naturalKey, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/role-hierarchy-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, nk = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("NK=")) nk = line.substring(3);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), nk, hash, line.substring(3)));
            }
        }
        assertEquals(3, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void reproducesTheRealEdgePart1PersistedInIiqNative() throws Exception {
        JsonNode real = golden().get(0).rest();
        // values copied from the iiq_native.sql COPY data for kf_role_hierarchy (HR Employee -INHERITANCE-> hritrole)
        assertEquals("32:7f0001019fbf1a17819fc7e6276a0ee511:INHERITANCE32:7f000101a0cf1f4c81a111f0f19b5712",
                RoleHierarchyRepository.naturalKey(real));
        assertEquals("0b2af2ee-5648-3d1f-918e-dd63cd6da132", RoleHierarchyRepository.id(real));
        assertEquals("5828b3965474b51eead07430046aeb622c3005b0074c3fa843c81f2f0d3de833",
                RoleHierarchyRepository.recordHash(real));
        assertEquals("7f000101-9fbf-1a17-819f-c7e6276a0ee5", RoleHierarchyRepository.roleUuid("7f0001019fbf1a17819fc7e6276a0ee5"));
        assertEquals("7f000101-a0cf-1f4c-81a1-11f0f19b5712", RoleHierarchyRepository.roleUuid("7f000101a0cf1f4c81a111f0f19b5712"));
    }

    @Test
    void naturalKeyIdAndHashMatchPart1() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.naturalKey(), RoleHierarchyRepository.naturalKey(g.rest()), "src_natural_key " + g.rest());
            assertEquals(g.id(), RoleHierarchyRepository.id(g.rest()), "rolehierarchyid " + g.rest());
            assertEquals(g.hash(), RoleHierarchyRepository.recordHash(g.rest()), "record_hash " + g.rest());
        }
    }

    @Test
    void edgeIdentityIsOwnerTypeAndRelatedRole() throws Exception {
        List<Golden> gs = golden();
        // same two roles, INHERITANCE vs REQUIREMENT -> two different edges
        assertNotEquals(gs.get(0).id(), gs.get(1).id());
        // direction matters: swapping owner and related role is a different edge
        ObjectNode swapped = gs.get(0).rest().deepCopy();
        swapped.put("source_role_id", gs.get(0).rest().get("related_role_source_id").asText());
        swapped.put("related_role_source_id", gs.get(0).rest().get("source_role_id").asText());
        assertNotEquals(gs.get(0).id(), RoleHierarchyRepository.id(swapped));
        // names are not part of the key (but are part of the hash)
        ObjectNode renamed = gs.get(0).rest().deepCopy();
        renamed.put("role_name", "Renamed").put("related_role_name", "Also renamed");
        assertEquals(gs.get(0).id(), RoleHierarchyRepository.id(renamed));
        assertNotEquals(gs.get(0).hash(), RoleHierarchyRepository.recordHash(renamed));
    }

    @Test
    void responseCarriesOnlyTheFiveBusinessFields() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("source_role_id", "role_name", "related_role_source_id", "related_role_name",
                "relationship_type"), keys);
    }

    @Test
    void bindsOwnerRelatedRoleDerivedColumnsAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-23"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("0b2af2ee-5648-3d1f-918e-dd63cd6da132", b.get(1));
        assertEquals("7f0001019fbf1a17819fc7e6276a0ee5", b.get(2), "owning role raw id");
        assertEquals("7f000101-9fbf-1a17-819f-c7e6276a0ee5", b.get(3), "role_id uuid");
        assertEquals("HR Employee", b.get(4));
        assertEquals("7f000101a0cf1f4c81a111f0f19b5712", b.get(5), "related role raw id");
        assertEquals("7f000101-a0cf-1f4c-81a1-11f0f19b5712", b.get(6), "related_role_id uuid");
        assertEquals("hritrole", b.get(7));
        assertEquals("INHERITANCE", b.get(8));
        assertEquals("IdentityIQ", b.get(9));
        assertEquals("native_iiq_java_api", b.get(10));
        assertEquals("sailpoint.object.Bundle", b.get(11));
        assertEquals("7f0001019fbf1a17819fc7e6276a0ee5", b.get(12), "src_object_id = owning role id");
        assertEquals(g.naturalKey(), b.get(13));
        assertEquals("run-23", b.get(14));
        assertEquals(g.hash(), b.get(15));
        assertEquals(15, b.size());
    }

    @Test
    void nonGuidRoleIdsUseTheNamedFallbackAndNullNamesBindNull() throws Exception {
        Golden g = golden().get(2);
        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), g.rest(), "run-23");
        Map<Integer, Object> b = jdbc.executions.get(0);
        assertEquals(CanonicalIds.deterministicUuid("native-role|not-a-guid"), b.get(3));
        assertEquals(CanonicalIds.deterministicUuid("native-role|also-not-a-guid"), b.get(6));
        assertNull(b.get(4), "null role_name bound via setString(null), as Part 1 does");
        assertEquals("PERMIT", b.get(8));
    }

    @Test
    void ddlMatchesIiqNativeAndUpsertIsIdempotent() throws Exception {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("source_role_id text NOT NULL") && ddl.contains("related_role_source_id text NOT NULL")
                && ddl.contains("relationship_type text NOT NULL") && ddl.contains("src_natural_key text NOT NULL"));

        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (rolehierarchyid) DO UPDATE SET"));
        assertTrue(sql.contains("is_deleted = false") && sql.contains("deleted_at = null"));
        assertEquals(15, sql.chars().filter(ch -> ch == '?').count());

        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), golden().get(1).rest(), "run-1");
        repo.upsert(jdbc.connection(), golden().get(1).rest(), "run-2");
        assertEquals(jdbc.executions.get(0).get(1), jdbc.executions.get(1).get(1), "stable key across runs");
    }
}

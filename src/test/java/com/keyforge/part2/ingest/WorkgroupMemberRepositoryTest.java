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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_workgroup_member. The golden fixture was produced by Part 1's own NativeWorkgroupMembershipParser /
 * NativeWorkgroupMembershipRepository (edgeId, hash) / REST toJson; its FIRST TWO rows are the live members, and their
 * id and record_hash equal the rows Part 1 stored in iiq_native.sql. Covers the canonical-UUID columns, the pair key,
 * the GUID-or-refuse rule, Part 1's own pipe-joined hash (incl. workgroup_name), lineage and DDL.
 */
class WorkgroupMemberRepositoryTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "id", "workgroup_id", "identity_id", "member_name", "first_name", "last_name", "member_is_workgroup",
            "src_system", "src_interface", "src_object_type", "src_object_id", "src_natural_key", "extraction_run_id",
            "record_hash", "is_deleted", "deleted_at", "extracted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkgroupMemberRepository repo = new WorkgroupMemberRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/workgroup-member-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), hash, line.substring(3)));
            }
        }
        assertEquals(4, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void reproducesTheRowsPart1StoredInIiqNative() throws Exception {
        List<Golden> gs = golden();
        // values from the iiq_native.sql COPY data for kf_workgroup_member
        assertEquals("b00be9b2-23da-34a9-90c9-19a5aa139584", WorkgroupMemberRepository.id(gs.get(0).rest()));
        assertEquals("cc4b5f3a1adac1eef79e0c5ca862a2289e77204597d17d4dae121a57aaa728e6", WorkgroupMemberRepository.recordHash(gs.get(0).rest()));
        assertEquals("3b4326a4-a539-39eb-81e4-bd8bb527e3cd", WorkgroupMemberRepository.id(gs.get(1).rest()));
        assertEquals("97f1b3e56ef2a7e43712df296d016f32a85fa987f28faad57d126a5429daaedb", WorkgroupMemberRepository.recordHash(gs.get(1).rest()));
    }

    @Test
    void keyAndHashMatchPart1AndNonGuidIdsAreRefused() throws Exception {
        List<Golden> gs = golden();
        for (Golden g : gs) {
            assertEquals(g.hash(), WorkgroupMemberRepository.recordHash(g.rest()), "record_hash parity for " + g.rest());
            if (!"THROWS".equals(g.id())) {
                assertEquals(g.id(), WorkgroupMemberRepository.id(g.rest()), "key parity");
            }
        }
        assertEquals("THROWS", gs.get(3).id(), "Part 1 refuses a non-GUID workgroup id");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> repo.upsert(new RecordingJdbc().connection(), gs.get(3).rest(), "run-1"));
        assertTrue(e.getMessage().contains("not UUID-shaped"), e.getMessage());
    }

    @Test
    void keyIsTheWorkgroupMemberPair() throws Exception {
        JsonNode m = golden().get(0).rest();
        String wg = m.get("workgroup_id").asText();
        String id = m.get("identity_id").asText();
        assertEquals(CanonicalIds.deterministicUuid("native-workgroup-member|" + CanonicalIds.canonicalUuid(wg) + "|"
                + CanonicalIds.canonicalUuid(id)), WorkgroupMemberRepository.id(m));
        // same identity in the other workgroup -> a different edge
        assertNotEquals(golden().get(0).id(), golden().get(1).id());
        assertEquals(golden().get(0).rest().get("identity_id"), golden().get(1).rest().get("identity_id"));
        // names do not move the key, but they are in the hash (incl. workgroup_name, which has no column)
        ObjectNode renamed = m.deepCopy();
        renamed.put("workgroup_name", "Renamed");
        assertEquals(WorkgroupMemberRepository.id(m), WorkgroupMemberRepository.id(renamed));
        assertNotEquals(WorkgroupMemberRepository.recordHash(m), WorkgroupMemberRepository.recordHash(renamed));
    }

    @Test
    void bindsCanonicalUuidColumnsRawLineageAndHash() throws Exception {
        Golden g = golden().get(0);
        String wgRaw = g.rest().get("workgroup_id").asText();
        String idRaw = g.rest().get("identity_id").asText();
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-97"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals(g.id(), b.get(1));
        assertEquals(CanonicalIds.canonicalUuid(wgRaw), b.get(2), "workgroup_id column = canonical uuid (kf_workgroup.workgroupid)");
        assertEquals(CanonicalIds.canonicalUuid(idRaw), b.get(3), "identity_id column = canonical uuid (kf_identity.userid)");
        assertEquals("spadmin", b.get(4));
        assertEquals(Boolean.FALSE, b.get(7));
        assertEquals("IdentityIQ", b.get(8));
        assertEquals("native_iiq_java_api", b.get(9));
        assertEquals("sailpoint.object.Identity.workgroups", b.get(10));
        assertEquals(idRaw, b.get(11), "src_object_id = raw member id");
        assertEquals(wgRaw + "|" + idRaw, b.get(12), "src_natural_key = raw workgroup|identity");
        assertEquals("run-97", b.get(13));
        assertEquals(g.hash(), b.get(14));
        assertEquals(14, b.size());

        RecordingJdbc sparse = new RecordingJdbc();
        repo.upsert(sparse.connection(), golden().get(2).rest(), "run-97");
        assertSame(NULL, sparse.executions.get(0).get(7), "non-boolean member_is_workgroup -> SQL NULL");
        assertNull(sparse.executions.get(0).get(4), "null member_name");
    }

    @Test
    void ddlAndUpsertMatchIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("workgroup_id uuid") && ddl.contains("identity_id uuid"));
        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (id) DO UPDATE SET"));
        assertTrue(sql.contains("is_deleted = false") && sql.contains("deleted_at = null"));
        assertEquals(14, sql.chars().filter(ch -> ch == '?').count());
        assertEquals("workgroupmember", repo.resource());
    }
}

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
 * kf_certification_entity. The golden fixture was produced by Part 1's own NativeCertificationEntityParser /
 * Repository / REST toJson; its FIRST TWO rows are the real entities Part 1 stored in iiq_native.sql (rebuilt from
 * the dump), so their key and hash are Part 1's stored values. Covers key + fallbacks (identity, not name), the
 * 11-field hash, the parent link, DDL and binding.
 */
class CertificationEntityTableTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "certificationentityid", "source_id", "certification_id", "identity", "application", "native_identity",
            "account_group", "first_name", "last_name", "full_name", "reference_attribute", "schema_object_type",
            "snapshot_id", "pending_certification", "type", "summary_status", "entity_delegated",
            "entity_delegation_status", "composite_score", "target_id", "target_name", "target_display_name",
            "completed", "created_at", "modified_at", "owner_id", "owner_name", "action_status", "action_decision_date",
            "action_remediation_action", "action_actor_name", "action_actor_display_name", "action_comments",
            "record_hash", "source_system", "source_interface", "source_object_type", "extraction_run_id",
            "extracted_at", "is_deleted", "deleted_at");

    private static final Set<String> HASHED = Set.of("source_id", "certification_id", "identity", "application",
            "native_identity", "type", "summary_status", "completed", "action_status", "action_decision_date",
            "action_actor_name");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", CertificationEntityTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/certification-entity-part1-golden.txt");
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
    void reproducesTheTwoRealEntitiesPart1StoredInIiqNative() throws Exception {
        List<Golden> gs = golden();
        // values from the iiq_native.sql COPY data for kf_certification_entity
        assertEquals("7f000101-a08f-1fbf-81a0-a5bc42b327f2", CertificationEntityTable.certificationEntityId(gs.get(0).rest()));
        assertEquals("9ff048b11d9daeca0d37b17720fa33210f7589bcc96b2909416b66e5fb8c5afe", CertificationEntityTable.recordHash(gs.get(0).rest()));
        assertEquals("7f000101-a08f-1fbf-81a0-a5d105ce282c", CertificationEntityTable.certificationEntityId(gs.get(1).rest()));
        assertEquals("cddb864a8e4e04809b8f57c4d3e7d254602c87360b722505cd4f82b9a35eda41", CertificationEntityTable.recordHash(gs.get(1).rest()));
    }

    @Test
    void keyAndHashMatchPart1IncludingFallbacks() throws Exception {
        List<Golden> gs = golden();
        for (Golden g : gs) {
            assertEquals(g.hash(), CertificationEntityTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), CertificationEntityTable.certificationEntityId(g.rest()), "key parity");
        }
        assertEquals(CanonicalIds.deterministicUuid("native-certification-entity|Jane Doe – café ✓"), gs.get(2).id(),
                "null id -> the entity's identity (not a name)");
        assertEquals(CanonicalIds.deterministicUuid("native-certification-entity|not-a-guid"), gs.get(3).id());
    }

    @Test
    void entityKeyIsItsOwnIdNotTheParentCertification() throws Exception {
        JsonNode e = golden().get(0).rest();
        String parent = e.get("certification_id").asText();
        assertNotEquals(CanonicalIds.canonicalUuid(parent), CertificationEntityTable.certificationEntityId(e));
        ObjectNode moved = e.deepCopy();
        moved.put("certification_id", "7f000101aaaaaaaaaaaaaaaaaaaaaaaa");
        assertEquals(CertificationEntityTable.certificationEntityId(e), CertificationEntityTable.certificationEntityId(moved),
                "the key does not depend on the parent");
        assertNotEquals(CertificationEntityTable.recordHash(e), CertificationEntityTable.recordHash(moved),
                "but the parent link is part of the hash");
    }

    @Test
    void hashCoversOnlyPart1sElevenFields() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : CertificationEntityTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 99);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            if (HASHED.contains(c.name)) {
                assertNotEquals(g.hash(), CertificationEntityTable.recordHash(changed), c.name + " must be hashed");
            } else {
                assertEquals(g.hash(), CertificationEntityTable.recordHash(changed), c.name + " is NOT hashed in Part 1");
            }
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(keys, CertificationEntityTable.BUSINESS.stream().map(c -> c.name).toList());
        assertEquals(32, keys.size());
    }

    @Test
    void ddlAndUpsertMatchIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("certificationentityid uuid PRIMARY KEY") && ddl.contains("composite_score integer"));
        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (certificationentityid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(38, vals.chars().filter(ch -> ch == '?').count(), "pk + 32 + record_hash + 4 lineage");
    }

    @Test
    void bindsParentLinkHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-67"));
        Map<Integer, Object> b = jdbc.executions.get(0);
        assertEquals(g.id(), b.get(1));
        assertEquals(g.rest().get("certification_id").asText(), b.get(col("certification_id")), "raw parent id");
        int hashIdx = 2 + CertificationEntityTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.CertificationEntity", b.get(hashIdx + 3));
        assertEquals("run-67", b.get(hashIdx + 4));

        RecordingJdbc sparse = new RecordingJdbc();
        repo.upsert(sparse.connection(), golden().get(2).rest(), "run-67");
        assertEquals(0, sparse.executions.get(0).get(col("composite_score")), "0 is a value");
        assertSame(NULL, sparse.executions.get(0).get(col("entity_delegated")));
    }

    private static int col(String name) {
        for (int i = 0; i < CertificationEntityTable.BUSINESS.size(); i++) {
            if (CertificationEntityTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

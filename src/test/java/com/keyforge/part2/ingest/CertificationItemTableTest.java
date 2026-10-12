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
 * kf_certification_item. The golden fixture was produced by Part 1's own NativeCertificationItemParser / Repository /
 * REST toJson; its FIRST TWO rows are real items Part 1 stored in iiq_native.sql (rebuilt from the dump), so their key and
 * hash are Part 1's stored values. Covers key + fallback, the 20-field hash, parent links, trailing DDL and binding.
 */
class CertificationItemTableTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "certificationitemid", "source_id", "certification_id", "entity_id", "identity", "type", "sub_type", "bundle",
            "bundle_assignment_id", "exception_application", "exception_attribute_name", "exception_attribute_value",
            "exception_permission_target", "exception_permission_right", "account_group", "phase", "summary_status",
            "completed", "last_decision", "expiration_date", "finished_date", "iiq_elevated_access", "reviewed",
            "delegated", "acted_upon", "historical", "expired", "target_id", "target_name", "short_description",
            "violation_summary", "application_names", "classification_names", "action_status", "action_decision_date",
            "action_decision_certification_id", "action_remediation_action", "action_actor_name",
            "action_actor_display_name", "action_comments", "action_completion_comments", "action_owner_name",
            "action_mitigation_expiration", "action_is_approved", "action_is_remediation", "action_is_mitigation",
            "action_is_delegation", "action_is_revoke_account", "action_is_auto_decision", "action_is_bulk_certified",
            "owner_id", "owner_name", "created_at", "modified_at", "record_hash", "source_system", "source_interface",
            "source_object_type", "extraction_run_id", "extracted_at", "is_deleted", "deleted_at", "policy_violation_id",
            "role_assignment");

    private static final Set<String> HASHED = Set.of("source_id", "certification_id", "entity_id", "identity", "type",
            "bundle", "exception_application", "exception_attribute_name", "exception_attribute_value", "summary_status",
            "completed", "last_decision", "action_status", "action_decision_date", "action_actor_name", "action_comments",
            "action_remediation_action", "iiq_elevated_access", "policy_violation_id", "role_assignment");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", CertificationItemTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/certification-item-part1-golden.txt");
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
    void reproducesRealItemsPart1StoredInIiqNative() throws Exception {
        List<Golden> gs = golden();
        // values from the iiq_native.sql COPY data for kf_certification_item
        assertEquals("7f000101-a08f-1fbf-81a0-a5bc429827da", CertificationItemTable.certificationItemId(gs.get(0).rest()));
        assertEquals("d403cdebeffcbec0a8f4faa2cb07853b581d3b884688861ed8661b8ca1b7ac0a", CertificationItemTable.recordHash(gs.get(0).rest()));
        assertEquals("7f000101-a08f-1fbf-81a0-a5bc429927db", CertificationItemTable.certificationItemId(gs.get(1).rest()));
        assertEquals("b9e59ca1c25cabeeb105104159353c901cc1c2895e1456d4a03933297cbe8599", CertificationItemTable.recordHash(gs.get(1).rest()));
    }

    @Test
    void keyAndHashMatchPart1IncludingFallback() throws Exception {
        List<Golden> gs = golden();
        for (Golden g : gs) {
            assertEquals(g.hash(), CertificationItemTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), CertificationItemTable.certificationItemId(g.rest()), "key parity");
        }
        assertEquals(CanonicalIds.deterministicUuid("native-certification-item|null"), gs.get(2).id(), "no name/identity fallback");
        assertEquals(CanonicalIds.deterministicUuid("native-certification-item|not-a-guid"), gs.get(3).id());
    }

    @Test
    void itemKeyIsItsOwnIdNotTheParents() throws Exception {
        JsonNode item = golden().get(0).rest();
        String key = CertificationItemTable.certificationItemId(item);
        assertNotEquals(CanonicalIds.canonicalUuid(item.get("certification_id").asText()), key, "not the certification id");
        assertNotEquals(CanonicalIds.canonicalUuid(item.get("entity_id").asText()), key, "not the entity id");
        ObjectNode moved = item.deepCopy();
        moved.put("certification_id", "7f000101aaaaaaaaaaaaaaaaaaaaaaaa").put("entity_id", "7f000101bbbbbbbbbbbbbbbbbbbbbbbb");
        assertEquals(key, CertificationItemTable.certificationItemId(moved), "key does not depend on the parents");
        assertNotEquals(golden().get(0).hash(), CertificationItemTable.recordHash(moved), "but both links are hashed");
    }

    @Test
    void hashCoversOnlyPart1sTwentyFields() throws Exception {
        Golden g = golden().get(0);
        int hashed = 0;
        for (ColumnSpec c : CertificationItemTable.SPEC.allBusiness()) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            if (HASHED.contains(c.name)) {
                assertNotEquals(g.hash(), CertificationItemTable.recordHash(changed), c.name + " must be hashed");
                hashed++;
            } else {
                assertEquals(g.hash(), CertificationItemTable.recordHash(changed), c.name + " is NOT hashed in Part 1");
            }
        }
        assertEquals(20, hashed);
    }

    @Test
    void responseFieldsAreExactlyTheMappedColumns() throws Exception {
        Set<String> keys = new HashSet<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        Set<String> mapped = new HashSet<>();
        CertificationItemTable.SPEC.allBusiness().forEach(c -> mapped.add(c.name));
        assertEquals(keys, mapped);
        assertEquals(55, mapped.size());
    }

    @Test
    void ddlAndUpsertMatchIiqNativeIncludingTrailingColumns() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("certificationitemid uuid PRIMARY KEY") && ddl.contains("application_names jsonb"));
        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (certificationitemid) DO UPDATE SET"));
        assertTrue(sql.contains("role_assignment = EXCLUDED.role_assignment"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(61, vals.chars().filter(ch -> ch == '?').count(), "pk + 55 + record_hash + 4 lineage");
    }

    @Test
    void bindsLinksJsonbHashAndLineage() throws Exception {
        Golden g = golden().get(2);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-71"));
        Map<Integer, Object> b = jdbc.executions.get(0);
        assertEquals(g.id(), b.get(1));
        assertEquals("c1", b.get(col("certification_id")));
        assertEquals("e1", b.get(col("entity_id")));
        assertEquals("[\"A\",\"B\"]", b.get(col("application_names")));
        assertEquals("[]", b.get(col("classification_names")), "empty array kept");
        assertSame(NULL, b.get(col("iiq_elevated_access")));
        assertEquals("pv1", b.get(col("policy_violation_id")), "trailing column bound");
        int hashIdx = 2 + CertificationItemTable.SPEC.allBusiness().size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.CertificationItem", b.get(hashIdx + 3));
        assertEquals("run-71", b.get(hashIdx + 4));
    }

    private static int col(String name) {
        List<ColumnSpec> all = CertificationItemTable.SPEC.allBusiness();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

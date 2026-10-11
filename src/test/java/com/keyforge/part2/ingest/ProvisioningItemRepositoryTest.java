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
 * kf_provisioning_item. Golden fixture produced by Part 1's own NativeProvisioningTxnParser (incl. its txn_source_id
 * back-fill and per-item object type), NativeProvisioningItemRepository (canonicalItemId / derivedNaturalKey /
 * recordHash) and the REST toJson. Covers: derived key (not the txn id), parent link, src_* columns, per-row object
 * type, the 16-field hash (instance excluded), DDL and binding.
 */
class ProvisioningItemRepositoryTest {

    private static final String TXN = "7f0001019fa71124819fb3e125ab1777";

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "provisioningitemid", "txn_source_id", "identity_name", "item_type", "operation", "application_name",
            "native_identity", "instance", "account_operation", "name", "value", "value_json", "assignment_id",
            "assignment", "permission_target", "permission_rights", "request_id", "item_index", "record_hash",
            "source_system", "source_interface", "source_object_type", "src_object_id", "src_natural_key",
            "extraction_run_id", "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProvisioningItemRepository repo = new ProvisioningItemRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String objectType, String naturalKey, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/provisioning-item-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, ot = null, nk = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("OT=")) ot = line.substring(3);
                else if (line.startsWith("NK=")) nk = line.substring(3);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), ot, nk, hash, line.substring(3)));
            }
        }
        assertEquals(3, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void keyNaturalKeyObjectTypeAndHashMatchPart1() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.id(), ProvisioningItemRepository.id(g.rest()), "provisioningitemid for " + g.rest());
            assertEquals(g.naturalKey(), ProvisioningItemRepository.naturalKey(g.rest()), "src_natural_key");
            assertEquals(g.objectType(), ProvisioningItemRepository.objectType(g.rest()), "source_object_type");
            assertEquals(g.hash(), ProvisioningItemRepository.recordHash(g.rest()), "record_hash");
        }
    }

    @Test
    void keyIsDerivedPerItemNotTheTransactionId() throws Exception {
        List<Golden> gs = golden();
        assertNotEquals(CanonicalIds.canonicalUuid(TXN), gs.get(0).id(), "not the txn id");
        assertNotEquals(gs.get(0).id(), gs.get(1).id(), "items of one txn are different rows");
        assertEquals(CanonicalIds.deterministicUuid("native-provisioning-item|" + TXN
                + "|ATTRIBUTE|Add|groups|\"cn=ops – café ✓\"|cn=ops – café ✓|0"), gs.get(0).id(),
                "value_json enters the key as Part 1's compact JSON text");
        // identity / application / request fields are not part of the key
        ObjectNode other = gs.get(0).rest().deepCopy();
        other.put("identity_name", "x").put("application_name", "y").put("request_id", "z").put("instance", "i");
        assertEquals(gs.get(0).id(), ProvisioningItemRepository.id(other));
    }

    @Test
    void everyItemCarriesItsParentTransaction() throws Exception {
        for (Golden g : golden()) {
            assertEquals(TXN, g.rest().get("txn_source_id").asText(), "Part 1 back-fills the parent txn id");
            assertTrue(g.naturalKey().startsWith(TXN + "|"));
        }
    }

    @Test
    void hashCoversEveryFieldExceptInstance() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : ProvisioningItemRepository.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 5);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                default -> changed.put(c.name, "changed-" + c.name);
            }
            if ("instance".equals(c.name)) {
                assertEquals(g.hash(), ProvisioningItemRepository.recordHash(changed), "instance is NOT hashed in Part 1");
            } else {
                assertNotEquals(g.hash(), ProvisioningItemRepository.recordHash(changed), c.name + " must be hashed");
            }
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(keys, ProvisioningItemRepository.BUSINESS.stream().map(c -> c.name).toList());
        assertEquals(17, keys.size());
    }

    @Test
    void ddlMatchesIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("value_json jsonb") && ddl.contains("item_index integer") && ddl.contains("assignment boolean"));
        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (provisioningitemid) DO UPDATE SET"));
        assertTrue(sql.contains("src_natural_key = EXCLUDED.src_natural_key"));
        assertEquals(25, sql.chars().filter(ch -> ch == '?').count(), "pk + 17 + record_hash + 3 lineage + 2 src_* + run id");
    }

    @Test
    void bindsDerivedColumnsPerRowObjectTypeAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-43"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals(g.id(), b.get(1));
        assertEquals(TXN, b.get(2), "txn_source_id");
        assertEquals("\"cn=ops – café ✓\"", b.get(12), "value_json bound as JSON");
        assertEquals(Boolean.TRUE, b.get(14));
        assertEquals(0, b.get(18), "item_index 0");
        assertEquals(g.hash(), b.get(19));
        assertEquals("IdentityIQ", b.get(20));
        assertEquals("native_iiq_java_api", b.get(21));
        assertEquals("sailpoint.object.ProvisioningPlan$AttributeRequest", b.get(22));
        assertEquals(TXN, b.get(23), "src_object_id = parent txn");
        assertEquals(TXN + "|ATTRIBUTE|0", b.get(24), "src_natural_key");
        assertEquals("run-43", b.get(25));
        assertEquals(25, b.size());

        RecordingJdbc perm = new RecordingJdbc();
        repo.upsert(perm.connection(), golden().get(2).rest(), "run-43");
        Map<Integer, Object> p = perm.executions.get(0);
        assertEquals("sailpoint.object.ProvisioningPlan$PermissionRequest", p.get(22));
        assertSame(NULL, p.get(12), "null value_json -> SQL NULL");
        assertSame(NULL, p.get(18), "null item_index -> SQL NULL");
        assertEquals(TXN + "|PERMISSION|null", p.get(24));

        RecordingJdbc list = new RecordingJdbc();
        repo.upsert(list.connection(), golden().get(1).rest(), "run-43");
        assertEquals("[\"a\",\"b\",{\"n\":1.5}]", list.executions.get(0).get(12));
    }
}

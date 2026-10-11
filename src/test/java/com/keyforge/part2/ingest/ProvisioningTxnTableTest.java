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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_provisioning_txn. Golden fixture produced by Part 1's own NativeProvisioningTxnParser / Repository / REST toJson.
 * Covers the txn key rules (canonical / non-GUID fallback / refusal), the raw-id link the items use, the all-field
 * hash, integers / booleans / jsonb, DDL and binding.
 */
class ProvisioningTxnTableTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "provisioningtxnid", "source_id", "name", "operation", "type", "status", "source", "integration", "forced",
            "identity_name", "identity_display_name", "application_name", "native_identity", "account_display_name",
            "certification_id", "certification_name", "access_request_id", "wait_work_item_id", "manual_work_item_id",
            "ticket_id", "retry_count", "timed_out", "filtered", "retry_request_id", "last_retry", "plan_result_status",
            "plan_result_request_id", "plan_result_errors", "account_request_operation", "request_id", "item_count",
            "owner_id", "owner_name", "created_at", "modified_at", "record_hash", "source_system", "source_interface",
            "source_object_type", "extraction_run_id", "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", ProvisioningTxnTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/provisioning-txn-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), hash, line.substring(3)));
            }
        }
        assertEquals(2, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void keyAndHashMatchPart1() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.hash(), ProvisioningTxnTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), ProvisioningTxnTable.provisioningTxnId(g.rest()), "key parity for " + g.rest());
        }
    }

    @Test
    void txnKeyRulesDifferFromTheItemRules() throws Exception {
        List<Golden> gs = golden();
        assertEquals("7f000101-9fa7-1124-819f-b3e125ab1777", gs.get(0).id(), "canonical IIQ id");
        assertEquals(CanonicalIds.deterministicUuid("native-provisioning-txn|txn-not-a-guid"), gs.get(1).id(),
                "non-GUID id -> name-based on the id");
        // null / blank ids are refused (Part 1's parser and repository both reject them)
        assertThrows(IllegalArgumentException.class, () -> CanonicalIds.provisioningTxnId(null));
        assertThrows(IllegalArgumentException.class, () -> CanonicalIds.provisioningTxnId("   "));
        ObjectNode noId = gs.get(0).rest().deepCopy();
        noId.putNull("source_id");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> repo.upsert(new RecordingJdbc().connection(), noId, "run-1"));
        assertTrue(e.getMessage().contains("source id is required"), e.getMessage());
        // unlike items, the txn key does not depend on content
        ObjectNode changed = gs.get(0).rest().deepCopy();
        changed.put("status", "Success").put("item_count", 9).put("name", "x");
        assertEquals(gs.get(0).id(), ProvisioningTxnTable.provisioningTxnId(changed));
    }

    @Test
    void itemsLinkByTheRawSourceIdNotTheUuidKey() throws Exception {
        Golden g = golden().get(0);
        String raw = g.rest().get("source_id").asText();
        // the items' txn_source_id / src_object_id / src_natural_key prefix is this raw id
        JsonNode item = mapper.readTree("{\"txn_source_id\":\"" + raw + "\",\"item_type\":\"ATTRIBUTE\",\"item_index\":0}");
        assertEquals(raw + "|ATTRIBUTE|0", ProvisioningItemRepository.naturalKey(item));
        assertNotEquals(ProvisioningTxnTable.provisioningTxnId(g.rest()), raw, "the uuid key is not what items store");
    }

    @Test
    void everyFieldIsHashed() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : ProvisioningTxnTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 77);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), ProvisioningTxnTable.recordHash(changed), c.name + " must be hashed");
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(keys, ProvisioningTxnTable.BUSINESS.stream().map(c -> c.name).toList());
        assertEquals(34, keys.size());
    }

    @Test
    void ddlAndUpsertMatchIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("provisioningtxnid uuid PRIMARY KEY") && ddl.contains("plan_result_errors jsonb"));
        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (provisioningtxnid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(40, vals.chars().filter(ch -> ch == '?').count(), "pk + 34 + record_hash + 4 lineage");
        assertEquals("provisioning-transactions", ProvisioningTxnTable.SPEC.resource());
    }

    @Test
    void bindsIntegersBooleansJsonbHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-47"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9fa7-1124-819f-b3e125ab1777", b.get(1));
        assertEquals("7f0001019fa71124819fb3e125ab1777", b.get(col("source_id")), "raw id kept for the item link");
        assertEquals(2, b.get(col("retry_count")));
        assertEquals(3, b.get(col("item_count")));
        assertEquals(Boolean.TRUE, b.get(col("filtered")));
        assertEquals("[\"Invalid name: App_IDJ0001002\",{\"n\":1.5}]", b.get(col("plan_result_errors")));
        assertEquals("2026-07-30T16:40:00.500Z", b.get(col("last_retry")));
        assertSame(NULL, b.get(col("certification_id")));

        int hashIdx = 2 + ProvisioningTxnTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.ProvisioningTransaction", b.get(hashIdx + 3));
        assertEquals("run-47", b.get(hashIdx + 4));

        RecordingJdbc sparse = new RecordingJdbc();
        repo.upsert(sparse.connection(), golden().get(1).rest(), "run-47");
        Map<Integer, Object> s = sparse.executions.get(0);
        assertEquals(0, s.get(col("retry_count")), "0 is a value");
        assertSame(NULL, s.get(col("item_count")));
        assertSame(NULL, s.get(col("forced")));
        assertEquals("[]", s.get(col("plan_result_errors")), "empty array kept");
    }

    private static int col(String name) {
        for (int i = 0; i < ProvisioningTxnTable.BUSINESS.size(); i++) {
            if (ProvisioningTxnTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

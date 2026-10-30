package com.keyforge.part2.ingest;

import com.keyforge.part2.testsupport.ExpectedKey;
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
 * kf_identity_request_item. Golden fixture produced by Part 1's own NativeIdentityRequestParser (incl. its
 * request_source_id back-fill from the parent), NativeIdentityRequestItemRepository and the REST toJson.
 * Covers: the item's own id is the key (not the parent's), both fallbacks, the parent link, the all-field hash.
 */
class IdentityRequestItemTableTest {

    private static final String PARENT = "7f0001019fa71124819fb3e12597174e";

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "identityrequestitemid", "source_id", "request_source_id", "request_name", "application", "attribute_name",
            "attribute_value", "operation", "managed_attribute_type", "assignment_id", "native_identity", "instance",
            "approver_name", "approval_state", "approved", "approval_complete", "rejected", "provisioning_state",
            "provisioning_engine", "provisioning_request_id", "provisioning_complete", "provisioning_failed",
            "compilation_status", "owner_name", "requester_comments", "expansion", "expansion_cause", "expansion_info",
            "retries", "iiq", "start_date", "end_date", "created_at", "modified_at", "record_hash", "source_system",
            "source_interface", "source_object_type", "extraction_run_id", "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", IdentityRequestItemTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/identity-request-item-part1-golden.txt");
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
    void keyAndHashMatchPart1() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.hash(), IdentityRequestItemTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(ExpectedKey.of(g.id(), g.rest(), "native-identity-request-item"), IdentityRequestItemTable.identityRequestItemId(g.rest()), "key parity for " + g.rest());
        }
    }

    @Test
    void keyIsTheItemsOwnIdNotTheParentRequest() throws Exception {
        List<Golden> gs = golden();
        assertEquals("7f000101-9fa7-1124-819f-b3e1259e1750", gs.get(0).id(), "canonical item id");
        assertNotEquals(CanonicalIds.canonicalUuid(PARENT), gs.get(0).id(), "not the parent request id");
        // two items of the same request never share a key
        assertNotEquals(gs.get(0).id(), gs.get(2).id());
        // fallbacks: item id, else the text "null" — never the parent id
        assertEquals(CanonicalIds.deterministicUuid("native-identity-request-item|null"), gs.get(1).id());
        assertEquals(CanonicalIds.deterministicUuid("native-identity-request-item|not-a-guid"), gs.get(2).id());
        // moving an item to another parent does not change its key
        ObjectNode moved = gs.get(0).rest().deepCopy();
        moved.put("request_source_id", "7f000101aaaaaaaaaaaaaaaaaaaaaaaa").put("request_name", "0000000099");
        assertEquals(gs.get(0).id(), IdentityRequestItemTable.identityRequestItemId(moved));
    }

    @Test
    void parentLinkIsCarriedInTheResponse() throws Exception {
        for (Golden g : golden()) {
            assertEquals(PARENT, g.rest().get("request_source_id").asText(),
                    "Part 1 back-fills request_source_id from the parent before the response is built");
        }
    }

    @Test
    void everyFieldIsHashed() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : IdentityRequestItemTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 99);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), IdentityRequestItemTable.recordHash(changed), c.name + " must be hashed");
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(keys, IdentityRequestItemTable.BUSINESS.stream().map(c -> c.name).toList());
        assertEquals(33, keys.size());
    }

    @Test
    void ddlAndUpsertMatchIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("identityrequestitemid uuid PRIMARY KEY") && ddl.contains("retries integer"));
        assertTrue(!ddl.contains("jsonb"), "no nested fields on request items");

        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (identityrequestitemid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(39, vals.chars().filter(ch -> ch == '?').count(), "pk + 33 + record_hash + 4 lineage");
        assertEquals("identity-request-items", IdentityRequestItemTable.SPEC.resource());
    }

    @Test
    void bindsKeyParentLinkIntegersNullsHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-31"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9fa7-1124-819f-b3e1259e1750", b.get(1));
        assertEquals("7f0001019fa71124819fb3e1259e1750", b.get(col("source_id")), "item id verbatim");
        assertEquals(PARENT, b.get(col("request_source_id")), "parent request id verbatim");
        assertEquals("0000000008", b.get(col("request_name")), "parent request number");
        assertEquals(3, b.get(col("retries")));
        assertEquals(Boolean.TRUE, b.get(col("provisioning_failed")));
        assertSame(NULL, b.get(col("assignment_id")));
        assertSame(NULL, b.get(col("start_date")));
        assertEquals("2026-07-31T02:00:00.123456789Z", b.get(col("modified_at")));

        int hashIdx = 2 + IdentityRequestItemTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.IdentityRequestItem", b.get(hashIdx + 3));
        assertEquals("run-31", b.get(hashIdx + 4));

        RecordingJdbc sparse = new RecordingJdbc();
        repo.upsert(sparse.connection(), golden().get(1).rest(), "run-31");
        repo.upsert(sparse.connection(), golden().get(2).rest(), "run-31");
        assertSame(NULL, sparse.executions.get(0).get(col("retries")), "null integer -> SQL NULL");
        assertSame(NULL, sparse.executions.get(0).get(col("approved")));
        assertEquals(0, sparse.executions.get(1).get(col("retries")), "0 is a value");
    }

    private static int col(String name) {
        for (int i = 0; i < IdentityRequestItemTable.BUSINESS.size(); i++) {
            if (IdentityRequestItemTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

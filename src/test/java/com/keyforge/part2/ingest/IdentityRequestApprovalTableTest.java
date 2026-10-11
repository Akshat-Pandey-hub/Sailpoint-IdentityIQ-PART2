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
 * kf_identity_request_approval. Golden fixture produced by Part 1's own NativeIdentityRequestParser (incl. its
 * request_source_id back-fill), NativeIdentityRequestApprovalRepository and the REST toJson. Covers the derived key
 * (not the parent id), the request / work-item links, null index, jsonb comments / sign_off, the all-field hash.
 */
class IdentityRequestApprovalTableTest {

    private static final String PARENT = "7f0001019fa71124819fb3e12597174e";

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "identityrequestapprovalid", "request_source_id", "request_name", "work_item_id", "work_item_type", "owner",
            "owner_id", "completer", "approved", "state", "state_key", "type_key", "start_date", "end_date",
            "approval_item_count", "approval_index", "comments", "sign_off", "record_hash", "source_system",
            "source_interface", "source_object_type", "extraction_run_id", "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo =
            new EntityRepository("iiq_native_part2_test", IdentityRequestApprovalTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/identity-request-approval-part1-golden.txt");
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
            assertEquals(g.hash(), IdentityRequestApprovalTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), IdentityRequestApprovalTable.identityRequestApprovalId(g.rest()), "key parity for " + g.rest());
        }
    }

    @Test
    void keyIsDerivedFromRequestWorkItemOwnerAndIndex() throws Exception {
        List<Golden> gs = golden();
        assertEquals(CanonicalIds.deterministicUuid("native-identity-request-approval|" + PARENT
                + "|7f0001019fa71124819fb3e125a2175a|spadmin|0"), gs.get(0).id());
        assertEquals(CanonicalIds.deterministicUuid("native-identity-request-approval|" + PARENT + "|null|null|null"),
                gs.get(1).id(), "null work item / owner / index -> text null (parent back-filled)");
        assertNotEquals(CanonicalIds.canonicalUuid(PARENT), gs.get(0).id(), "not the parent request id");
        // approvals of the same request are different rows
        assertNotEquals(gs.get(0).id(), gs.get(2).id());
        // non-key fields don't move the key
        ObjectNode decided = gs.get(0).rest().deepCopy();
        decided.put("state", "Rejected").put("approved", false).put("completer", "someone else");
        assertEquals(gs.get(0).id(), IdentityRequestApprovalTable.identityRequestApprovalId(decided));
        // a different position of the same work item / owner is a different approval
        ObjectNode next = gs.get(0).rest().deepCopy();
        next.put("approval_index", 1);
        assertNotEquals(gs.get(0).id(), IdentityRequestApprovalTable.identityRequestApprovalId(next));
    }

    @Test
    void linksToRequestAndWorkItemButNotToARequestItem() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(keys, IdentityRequestApprovalTable.BUSINESS.stream().map(c -> c.name).toList());
        assertTrue(keys.contains("request_source_id") && keys.contains("request_name") && keys.contains("work_item_id"));
        assertFalse(keys.stream().anyMatch(k -> k.contains("item_id") && !k.equals("work_item_id")),
                "no request-item link in the response");
        for (Golden g : golden()) {
            assertEquals(PARENT, g.rest().get("request_source_id").asText(), "Part 1 back-fills the parent request id");
        }
    }

    @Test
    void everyFieldIsHashed() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : IdentityRequestApprovalTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 42);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), IdentityRequestApprovalTable.recordHash(changed), c.name + " must be hashed");
        }
    }

    @Test
    void ddlAndUpsertMatchIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("identityrequestapprovalid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("comments jsonb") && ddl.contains("sign_off jsonb") && ddl.contains("approval_index integer"));

        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (identityrequestapprovalid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(23, vals.chars().filter(ch -> ch == '?').count(), "pk + 17 + record_hash + 4 lineage");
        assertEquals("identity-request-approvals", IdentityRequestApprovalTable.SPEC.resource());
    }

    @Test
    void bindsLinksJsonbIntegersNullsHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-37"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals(g.id(), b.get(1));
        assertEquals(PARENT, b.get(col("request_source_id")));
        assertEquals("0000000008", b.get(col("request_name")));
        assertEquals("7f0001019fa71124819fb3e125a2175a", b.get(col("work_item_id")));
        assertEquals(Boolean.TRUE, b.get(col("approved")));
        assertEquals(8, b.get(col("approval_item_count")));
        assertEquals(0, b.get(col("approval_index")), "index 0 is a value");
        assertTrue(((String) b.get(col("comments"))).startsWith("[{\"author\":\"spadmin\""));
        assertEquals("{\"n\":1.5,\"signer\":null}", b.get(col("sign_off")));

        int hashIdx = 2 + IdentityRequestApprovalTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.WorkflowSummary$ApprovalSummary", b.get(hashIdx + 3));
        assertEquals("run-37", b.get(hashIdx + 4));

        RecordingJdbc sparse = new RecordingJdbc();
        repo.upsert(sparse.connection(), golden().get(1).rest(), "run-37");
        repo.upsert(sparse.connection(), golden().get(2).rest(), "run-37");
        assertSame(NULL, sparse.executions.get(0).get(col("approval_index")));
        assertEquals("[]", sparse.executions.get(0).get(col("comments")), "empty array kept");
        assertEquals("{}", sparse.executions.get(0).get(col("sign_off")), "empty object kept");
        assertSame(NULL, sparse.executions.get(1).get(col("comments")), "null jsonb -> SQL NULL");
    }

    private static int col(String name) {
        for (int i = 0; i < IdentityRequestApprovalTable.BUSINESS.size(); i++) {
            if (IdentityRequestApprovalTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

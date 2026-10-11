package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_workitem. Golden fixture produced by Part 1's own NativeWorkItemParser / Repository / REST toJson for an approval
 * work item (request + workflow links), a certification work item (certification links) and a non-GUID id (refused).
 * Covers the no-fallback key, the 30-field hash subset, relationship columns, jsonb / integers / booleans, DDL, binding.
 */
class WorkItemTableTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "workitemid", "source_id", "name", "type", "state", "level", "requester_id", "requester_name", "assignee_id",
            "assignee_name", "owner_id", "owner_name", "completer", "completion_comments", "handler", "notification_name",
            "identity_request_id", "target_id", "target_name", "certification_id", "certification_entity_id",
            "certification_item_id", "entity_type", "certification_related", "workflow_case_id", "workflow_case_name",
            "expiration", "expiration_date", "notification", "wake_up_date", "escalation_count", "reminders",
            "reminders_sent", "expired", "expirable", "approval_set_item_count", "comments", "sign_offs", "owner_history",
            "approval_set_items", "created_at", "modified_at", "record_hash", "source_system", "source_interface",
            "source_object_type", "extraction_run_id", "extracted_at", "is_deleted", "deleted_at");

    /** Response fields Part 1 leaves OUT of record_hash. */
    private static final Set<String> NOT_HASHED = Set.of("requester_name", "assignee_name", "handler", "notification_name",
            "certification_entity_id", "certification_item_id", "entity_type", "workflow_case_name", "notification",
            "wake_up_date", "modified_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", WorkItemTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/workitem-part1-golden.txt");
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
            assertEquals(g.hash(), WorkItemTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            if (!"THROWS".equals(g.id())) {
                assertEquals(g.id(), WorkItemTable.workItemId(g.rest()), "workitemid parity");
            }
        }
    }

    @Test
    void workItemWithoutUsableIdIsRefusedLikePart1() throws Exception {
        Golden g = golden().get(2);
        assertEquals("THROWS", g.id());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> repo.upsert(new RecordingJdbc().connection(), g.rest(), "run-1"));
        assertTrue(e.getMessage().contains("source id is required"), e.getMessage());
    }

    @Test
    void relationshipsDependOnTheWorkItemType() throws Exception {
        JsonNode approval = golden().get(0).rest();
        assertEquals("Approval", approval.get("type").asText());
        assertEquals("0000000008", approval.get("identity_request_id").asText(), "request link stored verbatim");
        assertTrue(approval.get("certification_id").isNull());
        assertEquals("7f0001019fa71124819fb3e1259a1755", approval.get("workflow_case_id").asText());

        JsonNode cert = golden().get(1).rest();
        assertEquals("Certification", cert.get("type").asText());
        assertTrue(cert.get("identity_request_id").isNull());
        assertEquals("7f0001019c1234", cert.get("certification_id").asText());
        assertEquals(Boolean.TRUE, cert.get("certification_related").booleanValue());
    }

    @Test
    void hashCoversThirtyFieldsAndLeavesOutElevenLikePart1() throws Exception {
        Golden g = golden().get(0);
        int hashed = 0;
        for (ColumnSpec c : WorkItemTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 99);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            if (NOT_HASHED.contains(c.name)) {
                assertEquals(g.hash(), WorkItemTable.recordHash(changed), c.name + " is NOT hashed in Part 1");
            } else {
                assertNotEquals(g.hash(), WorkItemTable.recordHash(changed), c.name + " must be hashed");
                hashed++;
            }
        }
        assertEquals(30, hashed);
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(keys, WorkItemTable.BUSINESS.stream().map(c -> c.name).toList());
        assertEquals(41, keys.size());
    }

    @Test
    void ddlAndUpsertMatchIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("workitemid uuid PRIMARY KEY") && ddl.contains("approval_set_items jsonb"));
        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (workitemid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(47, vals.chars().filter(ch -> ch == '?').count(), "pk + 41 + record_hash + 4 lineage");
        assertEquals("work-items", WorkItemTable.SPEC.resource());
    }

    @Test
    void bindsRelationshipsJsonbIntegersHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-53"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9fa7-1124-819f-b3e125a2175a", b.get(1));
        assertEquals("7f0001019fa71124819fb3e125a2175a", b.get(col("source_id")), "raw id kept for approval/txn links");
        assertEquals("0000000008", b.get(col("identity_request_id")));
        assertEquals(0, b.get(col("escalation_count")), "0 is a value");
        assertEquals(8, b.get(col("approval_set_item_count")));
        assertEquals(Boolean.TRUE, b.get(col("expirable")));
        assertEquals("[]", b.get(col("sign_offs")), "empty array kept");
        assertEquals("[{\"oldOwner\":\"a\",\"newOwner\":\"b\",\"n\":1.5}]", b.get(col("owner_history")));
        assertSame(NULL, b.get(col("expiration")));

        int hashIdx = 2 + WorkItemTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.WorkItem", b.get(hashIdx + 3));
        assertEquals("run-53", b.get(hashIdx + 4));

        RecordingJdbc cert = new RecordingJdbc();
        repo.upsert(cert.connection(), golden().get(1).rest(), "run-53");
        Map<Integer, Object> c = cert.executions.get(0);
        assertSame(NULL, c.get(col("escalation_count")), "null integer -> SQL NULL");
        assertSame(NULL, c.get(col("comments")), "null jsonb -> SQL NULL");
        assertEquals("7f0001019c1234", c.get(col("certification_id")));
    }

    private static int col(String name) {
        for (int i = 0; i < WorkItemTable.BUSINESS.size(); i++) {
            if (WorkItemTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

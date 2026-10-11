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

import static com.keyforge.part2.testsupport.RecordingJdbc.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_identity_request. Golden fixture produced by Part 1's own NativeIdentityRequestParser / Repository /
 * RestService.toJson; row 1 carries the values of real request 0000000008 from iiq_native.sql, whose stored
 * record_hash it must reproduce. Covers the no-fallback key, all-field hash, integers, jsonb errors, binding.
 */
class IdentityRequestTableTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "identityrequestid", "source_id", "name", "type", "user_friendly_type", "state", "source", "source_object",
            "completion_status", "execution_status", "priority", "requester_id", "requester_display_name", "target_id",
            "target_display_name", "external_ticket_id", "process_id", "task_result_id", "executing", "failure",
            "rejected", "successful", "terminated", "incomplete", "iiq_only", "provisioning_complete", "end_date",
            "verified", "created_at", "modified_at", "owner_id", "owner_name", "errors", "item_count",
            "approval_count", "record_hash", "source_system", "source_interface", "source_object_type",
            "extraction_run_id", "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", IdentityRequestTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/identity-request-part1-golden.txt");
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
    void reproducesTheStoredHashOfARealPart1Request() throws Exception {
        Golden g = golden().get(0);
        // iiq_native.sql: kf_identity_request row 0000000008
        assertEquals("a0ba52ea128c6d5fcd2385137b671c4533aee318eb93894399000fcaa4b54ef4", IdentityRequestTable.recordHash(g.rest()));
        assertEquals("7f000101-9fa7-1124-819f-b3e12597174e", IdentityRequestTable.identityRequestId(g.rest()));
    }

    @Test
    void keyAndHashMatchPart1() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.hash(), IdentityRequestTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            if (!"THROWS".equals(g.id())) {
                assertEquals(g.id(), IdentityRequestTable.identityRequestId(g.rest()));
            }
        }
    }

    @Test
    void requestWithoutUsableIdFailsLikePart1() throws Exception {
        Golden g = golden().get(2);
        assertEquals("THROWS", g.id(), "Part 1 refuses this row");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> repo.upsert(new RecordingJdbc().connection(), g.rest(), "run-1"));
        assertTrue(e.getMessage().contains("source id is required"), e.getMessage());

        ObjectNode noId = g.rest().deepCopy();
        noId.putNull("source_id");
        assertThrows(IllegalArgumentException.class, () -> IdentityRequestTable.identityRequestId(noId));
    }

    @Test
    void everyFieldIsHashed() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : IdentityRequestTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 77);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), IdentityRequestTable.recordHash(changed), c.name + " must be hashed");
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(keys, IdentityRequestTable.BUSINESS.stream().map(c -> c.name).toList());
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
        assertTrue(ddl.contains("identityrequestid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("errors jsonb") && ddl.contains("item_count integer") && ddl.contains("approval_count integer"));

        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (identityrequestid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(40, vals.chars().filter(ch -> ch == '?').count(), "pk + 34 + record_hash + 4 lineage");
        assertEquals("identity-requests", IdentityRequestTable.SPEC.resource());
    }

    @Test
    void bindsKeyIntegersJsonbNullsHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-29"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9fa7-1124-819f-b3e12597174e", b.get(1));
        assertEquals("7f0001019fa71124819fb3e12597174e", b.get(col("source_id")), "source_id verbatim");
        assertEquals("0000000008", b.get(col("name")), "request number kept as text (leading zeros)");
        assertEquals(Boolean.TRUE, b.get(col("failure")));
        assertEquals(8, b.get(col("item_count")));
        assertEquals(1, b.get(col("approval_count")));
        assertSame(NULL, b.get(col("external_ticket_id")));
        assertTrue(((String) b.get(col("errors"))).startsWith("[\"sailpoint.connector.InvalidRequestException"));
        assertEquals("2026-07-30T16:36:11.909Z", b.get(col("end_date")));

        int hashIdx = 2 + IdentityRequestTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.IdentityRequest", b.get(hashIdx + 3));
        assertEquals("run-29", b.get(hashIdx + 4));

        RecordingJdbc sparse = new RecordingJdbc();
        repo.upsert(sparse.connection(), golden().get(1).rest(), "run-29");
        Map<Integer, Object> s = sparse.executions.get(0);
        assertEquals(0, s.get(col("item_count")), "0 is a value");
        assertSame(NULL, s.get(col("approval_count")), "null integer -> SQL NULL");
        assertSame(NULL, s.get(col("executing")));
        assertEquals("[]", s.get(col("errors")), "empty array kept");
    }

    private static int col(String name) {
        for (int i = 0; i < IdentityRequestTable.BUSINESS.size(); i++) {
            if (IdentityRequestTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

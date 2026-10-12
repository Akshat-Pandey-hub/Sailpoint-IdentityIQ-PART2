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
 * kf_task_result. Golden fixture = real live {@code /kfagent/task-results} rows paired with the taskresultid and
 * record_hash Part 1 stored for them in iiq_native.kf_task_result: "Full Text Index Refresh" (a message),
 * "Refresh Identity Cube" (statistics attributes), and "Update Identity spadmin", whose attributes hold
 * {@code sailpoint.object.WorkflowSummary@<identityHash>} — a Java toString that changes on every IIQ load, so it is
 * shown here with the value Part 1 stored. Covers key + fallbacks, the all-26-field hash, DDL, binding.
 */
class TaskResultTableTest {

    /** Column order of iiq_native.kf_task_result exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "taskresultid", "source_id", "name", "type", "completion_status", "definition_name", "launcher", "host",
            "target_name", "target_class", "target_id", "schedule", "progress", "percent_complete", "run_length",
            "pending_signoffs", "partitioned", "terminate_requested", "complete", "launched_at", "completed_at",
            "expiration_at", "verified_at", "messages", "attributes", "created_at", "modified_at", "record_hash",
            "source_system", "source_interface", "source_object_type", "extraction_run_id", "extracted_at",
            "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", TaskResultTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/task-result-part1-golden.txt");
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
    void keyAndHashMatchPart1StoredRows() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.id(), TaskResultTable.taskResultId(g.rest()), "taskresultid parity for " + g.rest());
            assertEquals(g.hash(), TaskResultTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
        }
        assertEquals("7f000101-9842-1229-8198-4a0a088a0e80", golden().get(0).id(), "canonical form of source_id");
    }

    @Test
    void fallbackKeysFollowPart1() throws Exception {
        ObjectNode r = golden().get(0).rest().deepCopy();
        r.put("source_id", "not-a-guid");
        assertEquals(CanonicalIds.deterministicUuid("native-task-result|not-a-guid"), TaskResultTable.taskResultId(r));
        r.putNull("source_id");
        assertEquals(CanonicalIds.deterministicUuid("native-task-result|Full Text Index Refresh"),
                TaskResultTable.taskResultId(r), "null source_id falls back to the name");
    }

    /**
     * Part 1 hashes attributes verbatim, so an identity-hash toString inside them gives a new record_hash whenever IIQ
     * reloads the object (161 of 181 live rows). Part 2 keeps that behaviour rather than masking it.
     */
    @Test
    void identityHashInsideAttributesChangesTheHashAsInPart1() throws Exception {
        Golden g = golden().get(1);
        String stored = g.rest().get("attributes").get("workflowSummary").asText();
        assertTrue(stored.startsWith("sailpoint.object.WorkflowSummary@"));
        ObjectNode reloaded = g.rest().deepCopy();
        String nextLoad = "sailpoint.object.WorkflowSummary@00000001";
        assertNotEquals(stored, nextLoad);
        ((ObjectNode) reloaded.get("attributes")).put("workflowSummary", nextLoad);
        assertNotEquals(g.hash(), TaskResultTable.recordHash(reloaded));
        assertEquals(g.id(), TaskResultTable.taskResultId(reloaded), "the key is unaffected");
    }

    @Test
    void everyFieldIsHashed() throws Exception {
        Golden g = golden().get(2);
        for (ColumnSpec c : TaskResultTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case INTEGER -> changed.put(c.name, 99);
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                case JSONB -> changed.putArray(c.name).add("other");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), TaskResultTable.recordHash(changed), c.name + " must be hashed");
        }
    }

    @Test
    void responseFieldsAreExactlyTheMappedColumns() throws Exception {
        Set<String> keys = new HashSet<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        Set<String> mapped = new HashSet<>();
        TaskResultTable.BUSINESS.forEach(c -> mapped.add(c.name));
        assertEquals(keys, mapped);
        assertEquals(26, mapped.size());
    }

    @Test
    void ddlMatchesIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("taskresultid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("percent_complete integer") && ddl.contains("complete boolean")
                && ddl.contains("messages jsonb") && ddl.contains("attributes jsonb"));

        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (taskresultid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(32, vals.chars().filter(ch -> ch == '?').count(), "pk + 26 + record_hash + 4 lineage");
        assertEquals("task-results", TaskResultTable.SPEC.resource());
    }

    @Test
    void bindsTaskResultFieldsHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-tr"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9842-1229-8198-4a0a088a0e80", b.get(1));
        assertEquals("7f0001019842122981984a0a088a0e80", b.get(col("source_id")));
        assertEquals("Full Text Index Refresh", b.get(col("name")));
        assertEquals("54c4fda028c842b3857cdd8ef8e9c44e", b.get(col("schedule")), "schedule name kept as raw text");
        assertEquals(0, b.get(col("run_length")));
        assertEquals(Boolean.TRUE, b.get(col("complete")));
        assertEquals("[{\"key\":\"Missing service definition: FullText\",\"type\":\"Error\"}]", b.get(col("messages")));
        assertEquals("{}", b.get(col("attributes")));
        assertEquals("2025-07-27T04:00:28.298Z", b.get(col("launched_at")));
        assertSame(NULL, b.get(col("expiration_at")));

        int hashIdx = 2 + TaskResultTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.TaskResult", b.get(hashIdx + 3));
        assertEquals("run-tr", b.get(hashIdx + 4));
    }

    private static int col(String name) {
        List<ColumnSpec> all = TaskResultTable.BUSINESS;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

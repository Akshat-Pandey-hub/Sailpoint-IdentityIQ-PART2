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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_workflow_definition. Golden fixture produced by Part 1's own NativeWorkflowParser (which computes the hash) /
 * NativeWorkflowRepository.id / REST toJson. Covers key + fallbacks, the camelCase 7-field hash, the definition text
 * conventions, derived src_* columns, DDL and binding.
 */
class WorkflowDefinitionRepositoryTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "workflowid", "source_id", "name", "type", "handler", "description", "task_type", "definition", "record_hash",
            "source_system", "source_interface", "source_object_type", "src_object_id", "src_natural_key",
            "extraction_run_id", "created_at", "modified_at", "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowDefinitionRepository repo = new WorkflowDefinitionRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String definition, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/workflow-definition-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, def = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("DEF=")) def = line.substring(4);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), def, hash, line.substring(3)));
            }
        }
        assertEquals(3, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void keyDefinitionTextAndHashMatchPart1() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.id(), WorkflowDefinitionRepository.id(g.rest()), "workflowid for " + g.rest());
            assertEquals(g.definition(), WorkflowDefinitionRepository.definition(g.rest()), "definition text");
            assertEquals(g.hash(), WorkflowDefinitionRepository.recordHash(g.rest()), "record_hash");
        }
        List<Golden> gs = golden();
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3461cc", gs.get(0).id());
        assertEquals(CanonicalIds.deterministicUuid("native-workflow|not-a-guid"), gs.get(1).id());
        assertEquals(CanonicalIds.deterministicUuid("native-workflow|null"), gs.get(2).id());
        assertEquals("null", gs.get(1).definition(), "JSON-null definition is the text null in Part 1");
    }

    @Test
    void hashCoversTheSevenParserFieldsButNotTimestamps() throws Exception {
        Golden g = golden().get(0);
        for (String f : List.of("source_id", "name", "type", "handler", "description", "task_type")) {
            ObjectNode changed = g.rest().deepCopy();
            changed.put(f, "changed");
            assertNotEquals(g.hash(), WorkflowDefinitionRepository.recordHash(changed), f + " must be hashed");
        }
        ObjectNode def = g.rest().deepCopy();
        def.putObject("definition").put("steps", 0);
        assertNotEquals(g.hash(), WorkflowDefinitionRepository.recordHash(def), "definition must be hashed");
        ObjectNode times = g.rest().deepCopy();
        times.put("created_at", "2001-01-01T00:00:00Z").put("modified_at", "2001-01-01T00:00:00Z");
        assertEquals(g.hash(), WorkflowDefinitionRepository.recordHash(times), "timestamps are NOT hashed in Part 1");
    }

    @Test
    void responseCarriesNineFields() throws Exception {
        List<String> keys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("source_id", "name", "type", "handler", "description", "task_type", "definition",
                "created_at", "modified_at"), keys);
    }

    @Test
    void ddlMatchesIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("workflowid uuid PRIMARY KEY") && ddl.contains("definition jsonb"));
        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (workflowid) DO UPDATE SET"));
        assertTrue(sql.contains("is_deleted = false") && sql.contains("deleted_at = null"));
        assertEquals(17, sql.chars().filter(ch -> ch == '?').count());
    }

    @Test
    void bindsDerivedColumnsDefinitionAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-59"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals(g.id(), b.get(1));
        assertEquals("7f0001019f061fdc819f34b0ac3461cc", b.get(2));
        assertEquals("LCM Provisioning – café ✓", b.get(3));
        assertEquals(g.definition(), b.get(8), "definition bound as compact JSON");
        assertEquals(g.hash(), b.get(9));
        assertEquals("IdentityIQ", b.get(10));
        assertEquals("native_iiq_java_api", b.get(11));
        assertEquals("sailpoint.object.Workflow", b.get(12));
        assertEquals("7f0001019f061fdc819f34b0ac3461cc", b.get(13), "src_object_id = source_id");
        assertEquals("LCM Provisioning – café ✓", b.get(14), "src_natural_key = workflow name");
        assertEquals("run-59", b.get(15));
        assertEquals("2025-07-27T03:42:26.205Z", b.get(16));
        assertEquals("2026-09-30T14:44:40.123456789Z", b.get(17));

        RecordingJdbc nul = new RecordingJdbc();
        repo.upsert(nul.connection(), golden().get(1).rest(), "run-59");
        assertEquals("null", nul.executions.get(0).get(8), "JSON-null definition stored as jsonb null, like Part 1");
        assertNull(nul.executions.get(0).get(16), "null created_at");
    }
}

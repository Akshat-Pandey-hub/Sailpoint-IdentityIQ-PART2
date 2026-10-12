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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_task_schedule. Golden fixture = real live {@code /kfagent/task-schedules} rows as they were when Part 1 stored
 * them (the run times and {@code arguments.nextActualFireTime} of these recurring schedules advance every run, so the
 * dump-time values are used), paired with the taskscheduleid and record_hash Part 1 stored in iiq_native.kf_task_schedule.
 * IIQ TaskSchedule ids are their names (not GUIDs), so every key comes from the name-based fallback.
 */
class TaskScheduleTableTest {

    /** Column order of iiq_native.kf_task_schedule exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "taskscheduleid", "source_id", "name", "description", "definition_name", "state", "new_state", "launcher",
            "host", "last_launch_error", "delete_on_finish", "last_execution_at", "next_execution_at",
            "next_actual_execution_at", "resume_at", "cron_expressions", "arguments", "created_at", "modified_at",
            "record_hash", "source_system", "source_interface", "source_object_type", "extraction_run_id",
            "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", TaskScheduleTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/task-schedule-part1-golden.txt");
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
    void keyAndHashMatchPart1StoredRows() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.id(), TaskScheduleTable.taskScheduleId(g.rest()), "taskscheduleid parity for " + g.rest());
            assertEquals(g.hash(), TaskScheduleTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
        }
    }

    @Test
    void nameShapedSourceIdUsesTheFallbackKey() throws Exception {
        Golden g = golden().get(1);
        assertEquals("Perform maintenance", g.rest().get("source_id").asText());
        assertNull(CanonicalIds.canonicalUuid("Perform maintenance"), "not a GUID");
        assertEquals(CanonicalIds.deterministicUuid("native-task-schedule|Perform maintenance"), g.id());

        ObjectNode noId = g.rest().deepCopy();
        noId.putNull("source_id");
        noId.put("name", "Only A Name");
        assertEquals(CanonicalIds.deterministicUuid("native-task-schedule|Only A Name"),
                TaskScheduleTable.taskScheduleId(noId), "null source_id falls back to the name");

        ObjectNode guid = g.rest().deepCopy();
        guid.put("source_id", "7f0001019842122981984a0a088a0e80");
        assertEquals("7f000101-9842-1229-8198-4a0a088a0e80", TaskScheduleTable.taskScheduleId(guid));
    }

    /** A recurring schedule's next run advances every execution, so each real run gives a new record_hash. */
    @Test
    void advancingRunTimesChangeTheHash() throws Exception {
        Golden g = golden().get(1);
        ObjectNode later = g.rest().deepCopy();
        later.put("last_execution_at", "2026-10-12T00:00:00Z");
        ((ObjectNode) later.get("arguments")).put("nextActualFireTime", "1791849600000");
        assertNotEquals(g.hash(), TaskScheduleTable.recordHash(later));
        assertEquals(g.id(), TaskScheduleTable.taskScheduleId(later), "the key is unaffected");
    }

    @Test
    void everyFieldIsHashed() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : TaskScheduleTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                case JSONB -> changed.putArray(c.name).add("other");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), TaskScheduleTable.recordHash(changed), c.name + " must be hashed");
        }
    }

    @Test
    void responseFieldsAreExactlyTheMappedColumns() throws Exception {
        Set<String> keys = new HashSet<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        Set<String> mapped = new HashSet<>();
        TaskScheduleTable.BUSINESS.forEach(c -> mapped.add(c.name));
        assertEquals(keys, mapped);
        assertEquals(18, mapped.size());
    }

    @Test
    void ddlMatchesIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("taskscheduleid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("delete_on_finish boolean") && ddl.contains("cron_expressions jsonb")
                && ddl.contains("arguments jsonb"));

        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (taskscheduleid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(24, vals.chars().filter(ch -> ch == '?').count(), "pk + 18 + record_hash + 4 lineage");
        assertEquals("task-schedules", TaskScheduleTable.SPEC.resource());
    }

    @Test
    void bindsTaskScheduleFieldsHashAndLineage() throws Exception {
        Golden g = golden().get(1);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-ts"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals(g.id(), b.get(1));
        assertEquals("Perform maintenance", b.get(col("source_id")));
        assertEquals("Perform Maintenance", b.get(col("definition_name")), "TaskDefinition name kept as raw text");
        assertEquals(Boolean.TRUE, b.get(col("delete_on_finish")));
        assertEquals("[\"0 0/5 * * * ?\"]", b.get(col("cron_expressions")));
        assertEquals("{\"executor\":\"Perform Maintenance\",\"nextActualFireTime\":\"1791555900000\"}",
                b.get(col("arguments")));
        assertEquals("2026-10-09T14:20:00Z", b.get(col("last_execution_at")));
        assertSame(NULL, b.get(col("resume_at")));
        assertSame(NULL, b.get(col("launcher")));

        int hashIdx = 2 + TaskScheduleTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.TaskSchedule", b.get(hashIdx + 3));
        assertEquals("run-ts", b.get(hashIdx + 4));
    }

    private static int col(String name) {
        List<ColumnSpec> all = TaskScheduleTable.BUSINESS;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/task-schedules} → {@code kf_task_schedule} (IIQ {@code TaskSchedule}), taken from Part 1's
 * iiq_native.sql + {@code NativeTaskScheduleRepository} / {@code NativeTaskScheduleRow}:
 * <ul>
 *   <li>PK {@code taskscheduleid uuid} = canonical source_id, fallback
 *       {@code deterministicUuid("native-task-schedule|"+id)} (name only when source_id is null).</li>
 *   <li>18 response fields, same names and order as the columns before {@code record_hash}:
 *       {@code delete_on_finish} boolean; {@code cron_expressions} / {@code arguments} jsonb; six timestamps.</li>
 *   <li>Relationships stay raw text, exactly as in Part 1: {@code definition_name} (the TaskDefinition),
 *       {@code launcher} (identity name). Task results reference a schedule by its name ({@code kf_task_result.schedule}).</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 18 fields (Part 1's {@code recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.TaskSchedule}</li>
 * </ul>
 * Part 1's DB extraction also runs a soft-delete sweep after a full scan; Part 2's engine does not sweep yet.
 */
public final class TaskScheduleTable {

    private TaskScheduleTable() {
    }

    public static final String RESOURCE = "task-schedules";
    public static final String TABLE = "kf_task_schedule";
    public static final String PK = "taskscheduleid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.TaskSchedule";

    /** Columns between the PK and {@code record_hash}, in iiq_native.kf_task_schedule order. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("description"),
            ColumnSpec.text("definition_name"),
            ColumnSpec.text("state"),
            ColumnSpec.text("new_state"),
            ColumnSpec.text("launcher"),
            ColumnSpec.text("host"),
            ColumnSpec.text("last_launch_error"),
            ColumnSpec.bool("delete_on_finish"),
            ColumnSpec.timestamptz("last_execution_at"),
            ColumnSpec.timestamptz("next_execution_at"),
            ColumnSpec.timestamptz("next_actual_execution_at"),
            ColumnSpec.timestamptz("resume_at"),
            ColumnSpec.jsonb("cron_expressions"),
            ColumnSpec.jsonb("arguments"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "TaskSchedule", RESOURCE, TABLE, PK, BUSINESS,
            TaskScheduleTable::taskScheduleId,
            TaskScheduleTable::recordHash,
            TaskScheduleTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    /** Part 1 {@code NativeTaskScheduleRepository.canonicalTaskScheduleId}. */
    public static String taskScheduleId(JsonNode row) {
        if (CanonicalIds.blank(text(row, "source_id")) && CanonicalIds.blank(text(row, "name"))) {
            return CanonicalIds.contentUuid("native-task-schedule", row); // neither IIQ id nor name
        }
        String sourceId = text(row, "source_id");
        String id = CanonicalIds.canonicalUuid(sourceId);
        if (id == null) {
            id = CanonicalIds.deterministicUuid("native-task-schedule|" + (sourceId == null ? text(row, "name") : sourceId));
        }
        return id;
    }

    /** Part 1 {@code NativeTaskScheduleRepository.recordHash}: all 18 fields, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, BUSINESS);
    }

    private static String ref(JsonNode row) {
        String id = text(row, "source_id");
        return "task schedule " + text(row, "name") + " (" + (id == null ? "<no source_id>" : id) + ")";
    }
}

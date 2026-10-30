package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/task-results} → {@code kf_task_result} (IIQ {@code TaskResult}), taken from Part 1's
 * iiq_native.sql + {@code NativeTaskResultRepository} / {@code NativeTaskResultRow}:
 * <ul>
 *   <li>PK {@code taskresultid uuid} = canonical source_id, fallback
 *       {@code deterministicUuid("native-task-result|"+id)} (name only when source_id is null).</li>
 *   <li>26 response fields, same names and order as the columns before {@code record_hash}: {@code percent_complete},
 *       {@code run_length}, {@code pending_signoffs} integer; {@code partitioned}, {@code terminate_requested},
 *       {@code complete} boolean; {@code messages} / {@code attributes} jsonb; six timestamps.</li>
 *   <li>Relationships stay raw text, exactly as in Part 1: {@code definition_name} (the TaskDefinition), {@code schedule}
 *       (the TaskSchedule name), {@code launcher} (identity name), {@code target_class} / {@code target_id} /
 *       {@code target_name} (the object the task ran against).</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 26 fields (Part 1's {@code recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.TaskResult}</li>
 * </ul>
 */
public final class TaskResultTable {

    private TaskResultTable() {
    }

    public static final String RESOURCE = "task-results";
    public static final String TABLE = "kf_task_result";
    public static final String PK = "taskresultid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.TaskResult";

    /** Columns between the PK and {@code record_hash}, in iiq_native.kf_task_result order. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("type"),
            ColumnSpec.text("completion_status"),
            ColumnSpec.text("definition_name"),
            ColumnSpec.text("launcher"),
            ColumnSpec.text("host"),
            ColumnSpec.text("target_name"),
            ColumnSpec.text("target_class"),
            ColumnSpec.text("target_id"),
            ColumnSpec.text("schedule"),
            ColumnSpec.text("progress"),
            ColumnSpec.integer("percent_complete"),
            ColumnSpec.integer("run_length"),
            ColumnSpec.integer("pending_signoffs"),
            ColumnSpec.bool("partitioned"),
            ColumnSpec.bool("terminate_requested"),
            ColumnSpec.bool("complete"),
            ColumnSpec.timestamptz("launched_at"),
            ColumnSpec.timestamptz("completed_at"),
            ColumnSpec.timestamptz("expiration_at"),
            ColumnSpec.timestamptz("verified_at"),
            ColumnSpec.jsonb("messages"),
            ColumnSpec.jsonb("attributes"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "TaskResult", RESOURCE, TABLE, PK, BUSINESS,
            TaskResultTable::taskResultId,
            TaskResultTable::recordHash,
            TaskResultTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    /** Part 1 {@code NativeTaskResultRepository.canonicalTaskResultId}. */
    public static String taskResultId(JsonNode row) {
        if (CanonicalIds.blank(text(row, "source_id")) && CanonicalIds.blank(text(row, "name"))) {
            return CanonicalIds.contentUuid("native-task-result", row); // neither IIQ id nor name
        }
        String sourceId = text(row, "source_id");
        String id = CanonicalIds.canonicalUuid(sourceId);
        if (id == null) {
            id = CanonicalIds.deterministicUuid("native-task-result|" + (sourceId == null ? text(row, "name") : sourceId));
        }
        return id;
    }

    /** Part 1 {@code NativeTaskResultRepository.recordHash}: all 26 fields, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, BUSINESS);
    }

    private static String ref(JsonNode row) {
        String id = text(row, "source_id");
        return "task result " + text(row, "name") + " (" + (id == null ? "<no source_id>" : id) + ")";
    }
}

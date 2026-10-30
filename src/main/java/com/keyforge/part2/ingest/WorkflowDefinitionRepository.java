package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.SchemaName;
import com.keyforge.part2.hash.RecordHash;
import com.keyforge.part2.id.CanonicalIds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code GET /kfagent/workflow-definitions} → {@code kf_workflow_definition}: IIQ {@code Workflow} DEFINITIONS (the
 * workflow templates/config — not running WorkflowCases and not WorkItems), reproducing Part 1's
 * {@code NativeWorkflowRepository} + {@code NativeWorkflowParser}. A dedicated {@link EntityWriter} because Part 1's
 * layout and rules differ from the generic tables:
 * <ul>
 *   <li>PK {@code workflowid uuid} = canonical source_id, fallback {@code deterministicUuid("native-workflow|"+source_id)}
 *       (a null id becomes the text "null", like Part 1).</li>
 *   <li>Derived columns the response omits: {@code src_object_id} = source_id and {@code src_natural_key} = the workflow
 *       {@code name}. Column order follows iiq_native: … record_hash, lineage, src_object_id, src_natural_key,
 *       extraction_run_id, created_at, modified_at, extracted_at, is_deleted, deleted_at.</li>
 *   <li>{@code definition} (jsonb) is the whole workflow definition. Part 1's parser keeps it as the raw JSON text
 *       ({@code node.toString()}), so a JSON-null definition is the text {@code null}: hashed as "null" and stored as
 *       jsonb {@code null} (not SQL NULL). Part 2 does the same.</li>
 *   <li>{@code record_hash} is computed the way Part 1's PARSER does it: 7 fields under camelCase keys
 *       ({@code sourceId, name, type, handler, description, taskType, definition}); created_at / modified_at are NOT
 *       hashed.</li>
 * </ul>
 * Lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.Workflow}.
 */
public final class WorkflowDefinitionRepository implements EntityWriter {

    public static final String RESOURCE = "workflow-definitions";
    public static final String TABLE = "kf_workflow_definition";
    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Workflow";

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String upsertSql;

    public WorkflowDefinitionRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        this.createTableSql = "CREATE TABLE IF NOT EXISTS " + table + " (\n"
                + "    workflowid uuid PRIMARY KEY,\n"
                + "    source_id text,\n"
                + "    name text,\n"
                + "    type text,\n"
                + "    handler text,\n"
                + "    description text,\n"
                + "    task_type text,\n"
                + "    definition jsonb,\n"
                + "    record_hash text,\n"
                + "    source_system text,\n"
                + "    source_interface text,\n"
                + "    source_object_type text,\n"
                + "    src_object_id text,\n"
                + "    src_natural_key text,\n"
                + "    extraction_run_id text,\n"
                + "    created_at timestamp with time zone,\n"
                + "    modified_at timestamp with time zone,\n"
                + "    extracted_at timestamp with time zone NOT NULL DEFAULT now(),\n"
                + "    is_deleted boolean NOT NULL DEFAULT false,\n"
                + "    deleted_at timestamp with time zone\n"
                + ")";
        this.upsertSql = "INSERT INTO " + table + " AS " + UpsertSql.ALIAS + " (workflowid, source_id, name, type, handler, description, task_type, "
                + "definition, record_hash, source_system, source_interface, source_object_type, src_object_id, "
                + "src_natural_key, extraction_run_id, created_at, modified_at) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz) "
                + UpsertSql.tail("workflowid", List.of("source_id", "name", "type", "handler", "description",
                        "task_type", "definition", "record_hash", "src_object_id", "src_natural_key", "created_at",
                        "modified_at"),
                        List.of("source_system", "source_interface", "source_object_type", "extraction_run_id"), true);
    }

    @Override
    public String entity() {
        return "WorkflowDefinition";
    }

    @Override
    public String resource() {
        return RESOURCE;
    }

    @Override
    public String targetTable() {
        return table;
    }

    @Override
    public void ensureSchemaAndTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql);
        }
    }

    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        String sourceId = text(row, "source_id");
        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            int i = 1;
            ps.setString(i++, id(row));
            ps.setString(i++, sourceId);
            ps.setString(i++, text(row, "name"));
            ps.setString(i++, text(row, "type"));
            ps.setString(i++, text(row, "handler"));
            ps.setString(i++, text(row, "description"));
            ps.setString(i++, text(row, "task_type"));
            ps.setString(i++, definition(row));
            ps.setString(i++, recordHash(row));
            ps.setString(i++, SOURCE_SYSTEM);
            ps.setString(i++, SOURCE_INTERFACE);
            ps.setString(i++, SOURCE_OBJECT_TYPE);
            ps.setString(i++, sourceId);                 // src_object_id
            ps.setString(i++, text(row, "name"));        // src_natural_key = workflow name
            ps.setString(i++, extractionRunId);
            ps.setString(i++, text(row, "created_at"));
            ps.setString(i, text(row, "modified_at"));
            try (ResultSet rs = ps.executeQuery()) {
                return UpsertSql.outcome(rs);
            }
        }
    }

    /** The PK {@link #upsert} writes. */
    @Override
    public String key(JsonNode row) {
        return id(row);
    }

    @Override
    public String keyColumn() {
        return "workflowid";
    }

    /** Part 1 {@code NativeWorkflowRepository.id}. */
    public static String id(JsonNode row) {
        if (CanonicalIds.blank(text(row, "source_id"))) {
            return CanonicalIds.contentUuid("native-workflow", row); // no IIQ id: a uuid from the full content
        }
        String source = text(row, "source_id");
        String x = CanonicalIds.canonicalUuid(source);
        return x == null ? CanonicalIds.deterministicUuid("native-workflow|" + source) : x;
    }

    /** Part 1 parser {@code n.path("definition").toString()}: compact JSON text; JSON null / absent → "null". */
    public static String definition(JsonNode row) {
        JsonNode n = row.get("definition");
        return n == null ? "null" : n.toString();
    }

    /** Part 1 {@code NativeWorkflowParser}: 7 camelCase-keyed fields, timestamps excluded. */
    public static String recordHash(JsonNode row) {
        Map<String, Object> business = new TreeMap<>();
        business.put("sourceId", text(row, "source_id"));
        business.put("name", text(row, "name"));
        business.put("type", text(row, "type"));
        business.put("handler", text(row, "handler"));
        business.put("description", text(row, "description"));
        business.put("taskType", text(row, "task_type"));
        business.put("definition", definition(row));
        return RecordHash.of(business);
    }

    @Override
    public String ref(JsonNode row) {
        return "workflow " + text(row, "name") + " (source_id=" + text(row, "source_id") + ")";
    }

    /** Exposed for tests. */
    public String createTableSql() {
        return createTableSql;
    }

    /** Exposed for tests. */
    public String upsertSql() {
        return upsertSql;
    }

    private static String text(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return (n == null || n.isNull()) ? null : (n.isValueNode() ? n.asText() : n.toString());
    }
}

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
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.bool;
import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.json;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/workitem-archives} → {@code kf_workitem_archive}: IIQ {@code WorkItemArchive}s, the immutable
 * record IIQ keeps when a work item is completed and archived. Reproduces Part 1's
 * {@code NativeWorkItemArchiveRepository} + {@code NativeWorkItemArchiveMapper}:
 * <ul>
 *   <li><b>Append-only (CEC).</b> {@code INSERT … ON CONFLICT (archiveid) DO NOTHING}: never updated, never deleted, no
 *       sweep. A re-run reports existing archives as {@link EntityRepository.Outcome#SKIPPED}.</li>
 *   <li>PK {@code archiveid} = canonical source_id (the archive's own id, NOT {@code work_item_id}). There is NO
 *       fallback: a null or non-GUID id is refused (the row fails), exactly as Part 1.</li>
 *   <li>CEC lineage envelope: {@code src_system} IdentityIQ, {@code src_object_type} sailpoint.object.WorkItemArchive,
 *       {@code src_object_id} (= source_id), {@code src_natural_key} (= archive name), {@code src_created} /
 *       {@code src_modified} (= created_at / modified_at), {@code src_event_ts} (= archived_ts),
 *       {@code src_interface} native_iiq_java_api, {@code extraction_run_id}, {@code raw_ref} and {@code derived_at}
 *       (both NULL, as in Part 1), {@code extracted_at}. No is_deleted / deleted_at.</li>
 *   <li>Relationships (raw IIQ ids / names, kept verbatim): {@code work_item_id} = the archived work item (deleted from
 *       {@code kf_workitem} once archived; approvals' and provisioning transactions' {@code work_item_id} point here),
 *       {@code identity_request_id} = the access request, {@code certification_id} / {@code certification_entity_id}
 *       / {@code certification_item_id} = the certification it belonged to, {@code target_class/_id/_name} = the
 *       object it acted on; requester / assignee / owner / completer are identity names.</li>
 *   <li>{@code record_hash} = Part 1's 29-field hash: every response field.</li>
 * </ul>
 */
public final class WorkItemArchiveRepository implements EntityWriter {

    public static final String RESOURCE = "workitem-archives";
    public static final String TABLE = "kf_workitem_archive";
    public static final String SRC_SYSTEM = "IdentityIQ";
    public static final String SRC_INTERFACE = "native_iiq_java_api";
    public static final String SRC_OBJECT_TYPE = "sailpoint.object.WorkItemArchive";

    /** The 20 text/boolean fields between archiveid and the jsonb fields, in kf_workitem_archive order. */
    private static final List<String> SCALARS = List.of(
            "source_id", "work_item_id", "name", "type", "state", "level", "requester", "assignee", "owner_name",
            "completer", "completion_comments", "is_signed", "target_class", "target_id", "target_name",
            "identity_request_id", "certification_id", "certification_entity_id", "certification_item_id",
            "entity_type");
    private static final List<String> JSONB = List.of(
            "signoffs", "comments", "owner_history", "system_attributes", "attributes");
    private static final List<String> TIMESTAMPS = List.of(
            "created_at", "modified_at", "expiration_ts", "archived_ts");

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String appendSql;

    public WorkItemArchiveRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n")
                .append("    archiveid uuid PRIMARY KEY");
        for (String c : SCALARS) {
            ddl.append(",\n    ").append(c).append(c.equals("is_signed") ? " boolean" : " text");
        }
        for (String c : JSONB) {
            ddl.append(",\n    ").append(c).append(" jsonb");
        }
        for (String c : TIMESTAMPS) {
            ddl.append(",\n    ").append(c).append(" timestamp with time zone");
        }
        this.createTableSql = ddl.append(",\n    record_hash text")
                .append(",\n    src_system text")
                .append(",\n    src_object_type text")
                .append(",\n    src_object_id text")
                .append(",\n    src_natural_key text")
                .append(",\n    src_created timestamp with time zone")
                .append(",\n    src_modified timestamp with time zone")
                .append(",\n    src_event_ts timestamp with time zone")
                .append(",\n    src_interface text")
                .append(",\n    extraction_run_id text")
                .append(",\n    raw_ref text")
                .append(",\n    derived_at timestamp with time zone")
                .append(",\n    extracted_at timestamp with time zone NOT NULL DEFAULT now()")
                .append("\n)").toString();
        this.appendSql = "INSERT INTO " + table + " (archiveid, " + String.join(", ", SCALARS) + ", "
                + String.join(", ", JSONB) + ", " + String.join(", ", TIMESTAMPS) + ", record_hash, src_system, "
                + "src_object_type, src_object_id, src_natural_key, src_created, src_modified, src_event_ts, "
                + "src_interface, extraction_run_id, raw_ref, derived_at) "
                + "VALUES (?::uuid, " + "?, ".repeat(SCALARS.size()) + "?::jsonb, ".repeat(JSONB.size())
                + "?::timestamptz, ".repeat(TIMESTAMPS.size())
                + "?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?, ?, ?, ?::timestamptz) "
                + "ON CONFLICT (archiveid) DO NOTHING RETURNING archiveid";
    }

    @Override
    public String entity() {
        return "WorkItemArchive";
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

    /** Appends one archive; an archive that already exists is left untouched ({@code SKIPPED}). */
    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        String archiveId = id(row);
        try (PreparedStatement ps = conn.prepareStatement(appendSql)) {
            int i = 1;
            ps.setString(i++, archiveId);
            for (String c : SCALARS) {
                if (c.equals("is_signed")) {
                    Boolean b = bool(row, c);
                    if (b == null) {
                        ps.setNull(i++, Types.BOOLEAN);
                    } else {
                        ps.setBoolean(i++, b);
                    }
                } else {
                    ps.setString(i++, text(row, c));
                }
            }
            for (String c : JSONB) {
                ps.setString(i++, json(row, c));
            }
            for (String c : TIMESTAMPS) {
                ps.setString(i++, text(row, c));
            }
            ps.setString(i++, recordHash(row));
            ps.setString(i++, SRC_SYSTEM);
            ps.setString(i++, SRC_OBJECT_TYPE);
            ps.setString(i++, text(row, "source_id"));      // src_object_id = archive getId()
            ps.setString(i++, text(row, "name"));           // src_natural_key = archive name
            ps.setString(i++, text(row, "created_at"));     // src_created
            ps.setString(i++, text(row, "modified_at"));    // src_modified
            ps.setString(i++, text(row, "archived_ts"));    // src_event_ts = archival time
            ps.setString(i++, SRC_INTERFACE);
            ps.setString(i++, extractionRunId);
            ps.setString(i++, null);                        // raw_ref: not wired, as in Part 1
            ps.setNull(i, Types.VARCHAR);                   // derived_at: extracted, not derived
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? EntityRepository.Outcome.INSERTED : EntityRepository.Outcome.SKIPPED;
            }
        }
    }

    /** Part 1 {@code NativeWorkItemArchiveRepository.canonicalArchiveId}: canonical source_id, no fallback. */
    public static String id(JsonNode row) {
        String id = CanonicalIds.canonicalUuid(text(row, "source_id"));
        if (id == null) {
            throw new IllegalArgumentException("WorkItemArchive source id is required; archiveid must derive from getId()");
        }
        return id;
    }

    /** Part 1 {@code NativeWorkItemArchiveRepository.recordHash}: all 29 fields. */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        for (String c : SCALARS) {
            b.put(c, c.equals("is_signed") ? bool(r, c) : text(r, c));
        }
        for (String c : JSONB) {
            b.put(c, json(r, c));
        }
        for (String c : TIMESTAMPS) {
            b.put(c, instant(r, c));
        }
        return RecordHash.of(b);
    }

    @Override
    public String ref(JsonNode row) {
        return "work item archive " + text(row, "name") + " (source_id=" + text(row, "source_id") + ")";
    }

    /** Exposed for tests. */
    public String createTableSql() {
        return createTableSql;
    }

    /** Exposed for tests. */
    public String appendSql() {
        return appendSql;
    }
}

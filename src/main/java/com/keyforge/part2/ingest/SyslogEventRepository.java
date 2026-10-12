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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/syslog-events} → {@code kf_syslog_event}: IIQ {@code SyslogEvent}s, IIQ's own immutable
 * operational log. Reproduces Part 1's {@code NativeSyslogEventRepository}:
 * <ul>
 *   <li><b>Append-only.</b> {@code INSERT … ON CONFLICT (syslogid) DO NOTHING}: never updated, never deleted, no
 *       soft-delete sweep. Existing events are {@link EntityRepository.Outcome#SKIPPED}.</li>
 *   <li>PK {@code syslogid} = canonical source_id, else {@code deterministicUuid("native-syslog|"+source_id)}, or
 *       {@code "native-syslog|"+quick_key} when source_id is null.</li>
 *   <li>11 response fields, all text except {@code created_at}. Column order = Part 1's CREATE TABLE
 *       ({@code classname} between {@code thread} and {@code line_number}).</li>
 *   <li>{@code record_hash} = Part 1's 10-field hash: every field EXCEPT {@code classname} ({@code created_at} as an
 *       {@code Instant}).</li>
 *   <li>Lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.SyslogEvent}.</li>
 *   <li>Relationships: none as keys; {@code username} / {@code server} / {@code classname} are raw log strings.</li>
 * </ul>
 */
public final class SyslogEventRepository implements EntityWriter {

    public static final String RESOURCE = "syslog-events";
    public static final String TABLE = "kf_syslog_event";
    public static final String SRC_SYSTEM = "IdentityIQ";
    public static final String SRC_INTERFACE = "native_iiq_java_api";
    public static final String SRC_OBJECT_TYPE = "sailpoint.object.SyslogEvent";

    /** The 10 text response fields, in kf_syslog_event column order (created_at follows). */
    static final List<String> TEXT_FIELDS = List.of(
            "source_id", "quick_key", "event_level", "server", "username", "thread", "classname", "line_number",
            "message", "stacktrace");

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String appendSql;

    public SyslogEventRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n")
                .append("    syslogid uuid PRIMARY KEY");
        for (String f : TEXT_FIELDS) {
            ddl.append(",\n    ").append(f).append(" text");
        }
        this.createTableSql = ddl.append(",\n    created_at timestamp with time zone")
                .append(",\n    record_hash text")
                .append(",\n    source_system text")
                .append(",\n    source_interface text")
                .append(",\n    source_object_type text")
                .append(",\n    extraction_run_id text")
                .append(",\n    extracted_at timestamp with time zone NOT NULL DEFAULT now()")
                .append("\n)").toString();
        this.appendSql = "INSERT INTO " + table + " (syslogid, " + String.join(", ", TEXT_FIELDS)
                + ", created_at, record_hash, source_system, source_interface, source_object_type, extraction_run_id) "
                + "VALUES (?::uuid, " + "?, ".repeat(TEXT_FIELDS.size()) + "?::timestamptz, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (syslogid) DO NOTHING RETURNING syslogid";
    }

    @Override
    public String entity() {
        return "SyslogEvent";
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

    /** Appends one event; an event that already exists is left untouched ({@code SKIPPED}). */
    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(appendSql)) {
            int i = 1;
            ps.setString(i++, id(row));
            for (String f : TEXT_FIELDS) {
                ps.setString(i++, text(row, f));
            }
            ps.setString(i++, text(row, "created_at"));
            ps.setString(i++, recordHash(row));
            ps.setString(i++, SRC_SYSTEM);
            ps.setString(i++, SRC_INTERFACE);
            ps.setString(i++, SRC_OBJECT_TYPE);
            ps.setString(i, extractionRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? EntityRepository.Outcome.INSERTED : EntityRepository.Outcome.SKIPPED;
            }
        }
    }

    /** Part 1 {@code NativeSyslogEventRepository.canonicalSyslogId}. */
    public static String id(JsonNode row) {
        String sourceId = text(row, "source_id");
        String id = CanonicalIds.canonicalUuid(sourceId);
        if (id == null) {
            id = CanonicalIds.deterministicUuid("native-syslog|" + (sourceId == null ? text(row, "quick_key") : sourceId));
        }
        return id;
    }

    /** Part 1 {@code NativeSyslogEventRepository.recordHash}: 10 fields, classname excluded. */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        for (String f : TEXT_FIELDS) {
            if (!f.equals("classname")) {
                b.put(f, text(r, f));
            }
        }
        b.put("created_at", instant(r, "created_at"));
        return RecordHash.of(b);
    }

    @Override
    public String ref(JsonNode row) {
        return "syslog event " + text(row, "quick_key") + " (source_id=" + text(row, "source_id") + ")";
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

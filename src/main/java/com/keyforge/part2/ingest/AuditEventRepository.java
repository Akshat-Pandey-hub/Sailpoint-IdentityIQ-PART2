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
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.json;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/audit-events} → {@code kf_audit_event}: IIQ {@code AuditEvent}s, immutable historical audit
 * records. Reproduces Part 1's {@code NativeAuditEventRepository}:
 * <ul>
 *   <li><b>Append-only.</b> {@code INSERT … ON CONFLICT (auditid) DO NOTHING}: never updated, never deleted, no sweep.
 *       A re-run reports existing events as {@link EntityRepository.Outcome#SKIPPED}.</li>
 *   <li>PK {@code auditid} = canonical source_id; otherwise {@code deterministicUuid("native-audit|"+source_id)}, or
 *       {@code "native-audit|"+action} when source_id is null (Java concatenation, so a null action is "null").</li>
 *   <li>Lineage: {@code source_system} IdentityIQ, {@code source_interface} native_iiq_java_api,
 *       {@code source_object_type} sailpoint.object.AuditEvent, {@code extraction_run_id}, {@code extracted_at}. No
 *       is_deleted / deleted_at.</li>
 *   <li>Relationships: none as keys. {@code audit_source} / {@code target} / {@code application} /
 *       {@code account_name} / {@code attribute_*} are the raw audit strings (names), kept verbatim as in Part 1.</li>
 *   <li>{@code record_hash} = all 19 response fields ({@code attributes} as compact JSON text, {@code created_at} as
 *       an {@code Instant}).</li>
 * </ul>
 */
public final class AuditEventRepository implements EntityWriter {

    public static final String RESOURCE = "audit-events";
    public static final String TABLE = "kf_audit_event";
    public static final String SRC_SYSTEM = "IdentityIQ";
    public static final String SRC_INTERFACE = "native_iiq_java_api";
    public static final String SRC_OBJECT_TYPE = "sailpoint.object.AuditEvent";

    /** The 17 plain-text response fields, in kf_audit_event column order (attributes and created_at follow). */
    private static final String[] TEXT_FIELDS = {
            "source_id", "action", "audit_source", "target", "application", "account_name", "instance",
            "attribute_name", "attribute_value", "interface_name", "server_host", "client_host", "tracking_id",
            "string1", "string2", "string3", "string4"};

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String appendSql;

    public AuditEventRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n")
                .append("    auditid uuid PRIMARY KEY");
        for (String f : TEXT_FIELDS) {
            ddl.append(",\n    ").append(f).append(" text");
        }
        this.createTableSql = ddl.append(",\n    attributes jsonb")
                .append(",\n    created_at timestamp with time zone")
                .append(",\n    record_hash text")
                .append(",\n    source_system text")
                .append(",\n    source_interface text")
                .append(",\n    source_object_type text")
                .append(",\n    extraction_run_id text")
                .append(",\n    extracted_at timestamp with time zone NOT NULL DEFAULT now()")
                .append("\n)").toString();
        this.appendSql = "INSERT INTO " + table + " (auditid, " + String.join(", ", TEXT_FIELDS)
                + ", attributes, created_at, record_hash, source_system, source_interface, source_object_type, "
                + "extraction_run_id) VALUES (?::uuid, " + "?, ".repeat(TEXT_FIELDS.length)
                + "?::jsonb, ?::timestamptz, ?, ?, ?, ?, ?) ON CONFLICT (auditid) DO NOTHING RETURNING auditid";
    }

    @Override
    public String entity() {
        return "AuditEvent";
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

    /** Appends one audit event; an event that already exists is left untouched ({@code SKIPPED}). */
    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(appendSql)) {
            int i = 1;
            ps.setString(i++, id(row));
            for (String f : TEXT_FIELDS) {
                ps.setString(i++, text(row, f));
            }
            ps.setString(i++, json(row, "attributes"));
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

    /** Part 1 {@code NativeAuditEventRepository.canonicalAuditId}. */
    public static String id(JsonNode row) {
        if (CanonicalIds.blank(text(row, "source_id"))) {
            return CanonicalIds.contentUuid("native-audit", row); // no IIQ id: a uuid from the full content
        }
        String sourceId = text(row, "source_id");
        String id = CanonicalIds.canonicalUuid(sourceId);
        if (id == null) {
            id = CanonicalIds.deterministicUuid("native-audit|" + (sourceId == null ? text(row, "action") : sourceId));
        }
        return id;
    }

    /** Part 1 {@code NativeAuditEventRepository.recordHash}: all 19 fields. */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        for (String f : TEXT_FIELDS) {
            b.put(f, text(r, f));
        }
        b.put("attributes", json(r, "attributes"));
        b.put("created_at", instant(r, "created_at"));
        return RecordHash.of(b);
    }

    @Override
    public String ref(JsonNode row) {
        return "audit event " + text(row, "action") + " (source_id=" + text(row, "source_id") + ")";
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

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
import static com.keyforge.part2.hash.HashValues.jsonText;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/access-history} → {@code kf_access_hist_entitlement}: IIQ Access-History entitlement captures
 * ({@code sailpoint.object.accesshistory.HistoricalEntitlementCapture}). Reproduces Part 1's
 * {@code NativeHistEntitlementCaptureRepository}:
 * <ul>
 *   <li><b>Append-only.</b> {@code INSERT … ON CONFLICT (hist_capture_id) DO NOTHING}: never updated, never deleted, no
 *       soft-delete sweep (Part 1's {@code NativeAccessHistoryImportService} has none). Existing captures are
 *       {@link EntityRepository.Outcome#SKIPPED}.</li>
 *   <li>PK {@code hist_capture_id} = canonical source_id, else {@code deterministicUuid("native-hist-ent|"+source_id)}
 *       (Java concatenation, so a null id seeds "native-hist-ent|null").</li>
 *   <li>Column order = iiq_native: {@code name}, {@code compressed_property_flag}, {@code compressed_property_flag_value}
 *       and {@code compressible_property_names} sit AFTER {@code extracted_at} (Part 1 added them with ALTER TABLE).</li>
 *   <li>{@code capture_json} is TEXT holding IIQ's serialized capture verbatim; the KF Agent re-hydrates it into JSON,
 *       and IIQ's serialization is compact, so compact re-serialization restores the stored text (a value the agent
 *       could not parse arrives as a string and is kept as-is). {@code compressible_property_names},
 *       {@code attributes}, {@code extended_attributes} are jsonb, and an EMPTY object/array is stored as NULL, as in
 *       Part 1.</li>
 *   <li>{@code record_hash} = Part 1's 20-field hash over the WIRE values read with {@code asText()}: booleans as
 *       "true"/"false", {@code effective_date} as its ISO text, {@code capture_json} as the raw serialized text, and
 *       {@code attributes} / {@code extended_attributes} as Jackson's {@code asText()} of an object, i.e. "" (their
 *       content is NOT hashed in Part 1).</li>
 *   <li>Lineage: {@code IdentityIQ} / {@code native_iiq_java_api} /
 *       {@code sailpoint.object.accesshistory.HistoricalEntitlementCapture}.</li>
 *   <li>Relationships (raw IIQ ids, kept verbatim): {@code identity_id} / {@code identity_name},
 *       {@code identity_entitlement_id}, {@code application_id} / {@code application_name}, {@code role_id} (when
 *       granted by a role), {@code request_item_id} / {@code pending_request_item_id},
 *       {@code certification_item_id}; {@code entity_id} is the access-history entity the capture belongs to.</li>
 * </ul>
 */
public final class AccessHistoryEntitlementRepository implements EntityWriter {

    public static final String RESOURCE = "access-history";
    public static final String TABLE = "kf_access_hist_entitlement";
    public static final String SRC_SYSTEM = "IdentityIQ";
    public static final String SRC_INTERFACE = "native_iiq_java_api";
    public static final String SRC_OBJECT_TYPE = "sailpoint.object.accesshistory.HistoricalEntitlementCapture";

    private enum Kind { TEXT, BOOL, TS, JSONB, CAPTURE }

    private record Col(String name, Kind kind) { }

    /** Response columns between hist_capture_id and record_hash, in iiq_native order. */
    private static final List<Col> LEADING = List.of(
            new Col("source_id", Kind.TEXT), new Col("entity_id", Kind.TEXT), new Col("entity_name", Kind.TEXT),
            new Col("identity_id", Kind.TEXT), new Col("identity_name", Kind.TEXT),
            new Col("identity_entitlement_id", Kind.TEXT), new Col("application_id", Kind.TEXT),
            new Col("application_name", Kind.TEXT), new Col("native_identity", Kind.TEXT),
            new Col("instance", Kind.TEXT), new Col("display_value", Kind.TEXT),
            new Col("attribute_name", Kind.TEXT), new Col("attribute_value", Kind.TEXT), new Col("type", Kind.TEXT),
            new Col("granted_by_role", Kind.BOOL), new Col("role_id", Kind.TEXT),
            new Col("request_item_id", Kind.TEXT), new Col("pending_request_item_id", Kind.TEXT),
            new Col("certification_item_id", Kind.TEXT), new Col("deleted", Kind.BOOL),
            new Col("effective_date", Kind.TS), new Col("extended_to_date", Kind.TS), new Col("latest", Kind.BOOL),
            new Col("compressed", Kind.BOOL), new Col("brief", Kind.BOOL), new Col("full_capture", Kind.BOOL),
            new Col("patch", Kind.BOOL), new Col("smart_hash", Kind.TEXT), new Col("full_hash", Kind.TEXT),
            new Col("json_format", Kind.TEXT), new Col("transform_type", Kind.TEXT),
            new Col("patch_doc_parent", Kind.TEXT), new Col("capture_json", Kind.CAPTURE),
            new Col("attributes", Kind.JSONB), new Col("extended_attributes", Kind.JSONB),
            new Col("created_at", Kind.TS), new Col("modified_at", Kind.TS));

    /** Response columns iiq_native places after extracted_at. */
    private static final List<Col> TRAILING = List.of(
            new Col("name", Kind.TEXT), new Col("compressed_property_flag", Kind.TEXT),
            new Col("compressed_property_flag_value", Kind.TEXT),
            new Col("compressible_property_names", Kind.JSONB));

    /** Part 1's hashed wire keys → the response field carrying the same value. */
    private static final Map<String, String> HASHED = new LinkedHashMap<>();

    static {
        String[][] pairs = {
                {"sourceId", "source_id"}, {"entityId", "entity_id"}, {"identityId", "identity_id"},
                {"identityEntitlementId", "identity_entitlement_id"}, {"applicationId", "application_id"},
                {"attributeName", "attribute_name"}, {"attributeValue", "attribute_value"}, {"type", "type"},
                {"grantedByRole", "granted_by_role"}, {"roleId", "role_id"}, {"requestItemId", "request_item_id"},
                {"certificationItemId", "certification_item_id"}, {"deleted", "deleted"},
                {"effectiveDate", "effective_date"}, {"latest", "latest"}, {"smartHash", "smart_hash"},
                {"fullHash", "full_hash"}, {"captureJson", "capture_json"}, {"attributes", "attributes"},
                {"extendedAttributes", "extended_attributes"}};
        for (String[] p : pairs) {
            HASHED.put(p[0], p[1]);
        }
    }

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String appendSql;

    public AccessHistoryEntitlementRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n")
                .append("    hist_capture_id uuid PRIMARY KEY");
        for (Col c : LEADING) {
            ddl.append(",\n    ").append(c.name()).append(' ').append(sqlType(c.kind()));
        }
        ddl.append(",\n    record_hash text")
                .append(",\n    source_system text")
                .append(",\n    source_interface text")
                .append(",\n    source_object_type text")
                .append(",\n    extraction_run_id text")
                .append(",\n    extracted_at timestamp with time zone NOT NULL DEFAULT now()");
        for (Col c : TRAILING) {
            ddl.append(",\n    ").append(c.name()).append(' ').append(sqlType(c.kind()));
        }
        this.createTableSql = ddl.append("\n)").toString();

        StringBuilder cols = new StringBuilder("hist_capture_id");
        StringBuilder vals = new StringBuilder("?::uuid");
        for (Col c : LEADING) {
            cols.append(", ").append(c.name());
            vals.append(", ").append(placeholder(c.kind()));
        }
        cols.append(", record_hash, source_system, source_interface, source_object_type, extraction_run_id");
        vals.append(", ?, ?, ?, ?, ?");
        for (Col c : TRAILING) {
            cols.append(", ").append(c.name());
            vals.append(", ").append(placeholder(c.kind()));
        }
        this.appendSql = "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ") "
                + "ON CONFLICT (hist_capture_id) DO NOTHING RETURNING hist_capture_id";
    }

    private static String sqlType(Kind k) {
        return switch (k) {
            case TEXT, CAPTURE -> "text";
            case BOOL -> "boolean";
            case TS -> "timestamp with time zone";
            case JSONB -> "jsonb";
        };
    }

    private static String placeholder(Kind k) {
        return switch (k) {
            case TS -> "?::timestamptz";
            case JSONB -> "?::jsonb";
            default -> "?";
        };
    }

    @Override
    public String entity() {
        return "HistoricalEntitlementCapture";
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

    /** Appends one capture; a capture that already exists is left untouched ({@code SKIPPED}). */
    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(appendSql)) {
            int i = 1;
            ps.setString(i++, id(row));
            for (Col c : LEADING) {
                bind(ps, i++, row, c);
            }
            ps.setString(i++, recordHash(row));
            ps.setString(i++, SRC_SYSTEM);
            ps.setString(i++, SRC_INTERFACE);
            ps.setString(i++, SRC_OBJECT_TYPE);
            ps.setString(i++, extractionRunId);
            for (Col c : TRAILING) {
                bind(ps, i++, row, c);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? EntityRepository.Outcome.INSERTED : EntityRepository.Outcome.SKIPPED;
            }
        }
    }

    private static void bind(PreparedStatement ps, int index, JsonNode row, Col c) throws SQLException {
        switch (c.kind()) {
            case BOOL -> {
                Boolean b = bool(row, c.name());
                if (b == null) {
                    ps.setNull(index, Types.BOOLEAN);
                } else {
                    ps.setBoolean(index, b);
                }
            }
            case JSONB -> ps.setString(index, jsonbOrNull(row, c.name()));
            case CAPTURE -> ps.setString(index, jsonText(row, c.name()));
            default -> ps.setString(index, text(row, c.name()));
        }
    }

    /** Part 1 {@code NativeJsonRead.jsonString}: compact JSON, but an empty object/array (or null) is NULL. */
    static String jsonbOrNull(JsonNode row, String field) {
        JsonNode n = row.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if ((n.isObject() || n.isArray()) && n.size() == 0) {
            return null;
        }
        return n.toString();
    }

    /** Part 1 {@code NativeHistEntitlementCaptureRepository.canonicalId}. */
    public static String id(JsonNode row) {
        String sourceId = text(row, "source_id");
        String id = CanonicalIds.canonicalUuid(sourceId);
        if (id == null) {
            id = CanonicalIds.deterministicUuid("native-hist-ent|" + sourceId);
        }
        return id;
    }

    /**
     * Part 1 {@code NativeHistEntitlementCaptureRepository.recordHash}: 20 wire keys, each read with
     * {@code asText()}. capture_json is restored to IIQ's serialized text first; every other value reads the same
     * from the response as from the wire (objects → "").
     */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : HASHED.entrySet()) {
            b.put(e.getKey(), e.getValue().equals("capture_json") ? jsonText(r, "capture_json") : text(r, e.getValue()));
        }
        return RecordHash.of(b);
    }

    @Override
    public String ref(JsonNode row) {
        return "access-history entitlement capture (source_id=" + text(row, "source_id") + ")";
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

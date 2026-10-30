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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.jsonText;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/access-history/certifications} → {@code kf_access_hist_certification}: IIQ Access-History
 * certifications ({@code sailpoint.object.accesshistory.HistoricalCertification}). Reproduces Part 1's
 * {@code NativeHistCertificationRepository}:
 * <ul>
 *   <li><b>Kept, updated only when IIQ changed it.</b> {@code INSERT … ON CONFLICT (hist_cert_id) DO UPDATE} only when a
 *       data column differs ({@link UpsertSql}): never deleted, no soft-delete sweep. Existing rows that are
 *       unchanged are {@link EntityRepository.Outcome#SKIPPED}; changed ones (e.g. a flag IIQ updates later) are
 *       {@link EntityRepository.Outcome#UPDATED}.</li>
 *   <li>PK {@code hist_cert_id} = canonical source_id, else {@code deterministicUuid("native-hist-cert|"+source_id)}
 *       (Java concatenation, so a null id seeds "native-hist-cert|null").</li>
 *   <li>11 response fields: {@code finished}, {@code signed}, {@code created_at}, {@code modified_at} timestamps;
 *       {@code cert_json} is TEXT holding IIQ's serialized certification verbatim (the KF Agent re-hydrates it into
 *       JSON; compact re-serialization restores the text; a value it could not parse arrives as a string and is kept
 *       as-is); the rest text.</li>
 *   <li>{@code record_hash} = Part 1's 8-field hash over the WIRE values read with {@code asText()}: source_id,
 *       cert_id, cert_type, cert_name, cert_display_name, finished and signed (their ISO text), cert_json (raw text).
 *       name, created_at, modified_at are NOT hashed.</li>
 *   <li>Lineage: {@code IdentityIQ} / {@code native_iiq_java_api} /
 *       {@code sailpoint.object.accesshistory.HistoricalCertification}.</li>
 *   <li>Relationships: {@code cert_id} / {@code cert_name} = the IIQ certification (raw id / name, kept verbatim).</li>
 * </ul>
 */
public final class AccessHistoryCertificationRepository implements EntityWriter {

    public static final String RESOURCE = "access-history/certifications";
    public static final String TABLE = "kf_access_hist_certification";
    public static final String SRC_SYSTEM = "IdentityIQ";
    public static final String SRC_INTERFACE = "native_iiq_java_api";
    public static final String SRC_OBJECT_TYPE = "sailpoint.object.accesshistory.HistoricalCertification";

    private static final String CERT_JSON = "cert_json";

    /** Response columns between hist_cert_id and record_hash, in iiq_native order. */
    static final List<String> COLUMNS = List.of(
            "source_id", "name", "cert_id", "cert_type", "cert_name", "cert_display_name", "finished", "signed",
            CERT_JSON, "created_at", "modified_at");

    private static boolean isTimestamp(String col) {
        return col.equals("finished") || col.equals("signed") || col.equals("created_at") || col.equals("modified_at");
    }

    /** Part 1's hashed wire keys → the response field carrying the same value. */
    private static final Map<String, String> HASHED = new LinkedHashMap<>();

    static {
        String[][] pairs = {
                {"sourceId", "source_id"}, {"certId", "cert_id"}, {"certType", "cert_type"},
                {"certName", "cert_name"}, {"certDisplayName", "cert_display_name"}, {"finished", "finished"},
                {"signed", "signed"}, {"certJson", CERT_JSON}};
        for (String[] p : pairs) {
            HASHED.put(p[0], p[1]);
        }
    }

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String appendSql;

    public AccessHistoryCertificationRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n")
                .append("    hist_cert_id uuid PRIMARY KEY");
        StringBuilder cols = new StringBuilder("hist_cert_id");
        StringBuilder vals = new StringBuilder("?::uuid");
        for (String c : COLUMNS) {
            ddl.append(",\n    ").append(c).append(isTimestamp(c) ? " timestamp with time zone" : " text");
            cols.append(", ").append(c);
            vals.append(isTimestamp(c) ? ", ?::timestamptz" : ", ?");
        }
        this.createTableSql = ddl.append(",\n    record_hash text")
                .append(",\n    source_system text")
                .append(",\n    source_interface text")
                .append(",\n    source_object_type text")
                .append(",\n    extraction_run_id text")
                .append(",\n    extracted_at timestamp with time zone NOT NULL DEFAULT now()")
                .append("\n)").toString();
        List<String> compared = new ArrayList<>(COLUMNS);
        compared.add("record_hash");
        this.appendSql = "INSERT INTO " + table + " AS " + UpsertSql.ALIAS + " (" + cols
                + ", record_hash, source_system, source_interface, source_object_type, extraction_run_id) VALUES ("
                + vals + ", ?, ?, ?, ?, ?) "
                + UpsertSql.tail("hist_cert_id", compared, List.of("source_system", "source_interface", "source_object_type", "extraction_run_id"), false);
    }

    @Override
    public String entity() {
        return "HistoricalCertification";
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

    /** Inserts one row; an existing one is updated only when a data column changed, else {@code SKIPPED}. */
    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(appendSql)) {
            int i = 1;
            ps.setString(i++, id(row));
            for (String c : COLUMNS) {
                ps.setString(i++, c.equals(CERT_JSON) ? jsonText(row, c) : text(row, c));
            }
            ps.setString(i++, recordHash(row));
            ps.setString(i++, SRC_SYSTEM);
            ps.setString(i++, SRC_INTERFACE);
            ps.setString(i++, SRC_OBJECT_TYPE);
            ps.setString(i, extractionRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return UpsertSql.outcome(rs);
            }
        }
    }

    /** Part 1 {@code NativeHistCertificationRepository.canonicalId}. */
    public static String id(JsonNode row) {
        if (CanonicalIds.blank(text(row, "source_id"))) {
            return CanonicalIds.contentUuid("native-hist-cert", row); // no IIQ id: a uuid from the full content
        }
        String sourceId = text(row, "source_id");
        String id = CanonicalIds.canonicalUuid(sourceId);
        if (id == null) {
            id = CanonicalIds.deterministicUuid("native-hist-cert|" + sourceId);
        }
        return id;
    }

    /** Part 1 {@code NativeHistCertificationRepository.recordHash}: 8 wire keys, each read with {@code asText()}. */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : HASHED.entrySet()) {
            b.put(e.getKey(), e.getValue().equals(CERT_JSON) ? jsonText(r, CERT_JSON) : text(r, e.getValue()));
        }
        return RecordHash.of(b);
    }

    @Override
    public String ref(JsonNode row) {
        return "access-history certification " + text(row, "cert_name") + " (source_id=" + text(row, "source_id") + ")";
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

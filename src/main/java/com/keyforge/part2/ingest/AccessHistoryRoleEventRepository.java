package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.SchemaName;
import com.keyforge.part2.id.CanonicalIds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static com.keyforge.part2.hash.HashValues.jsonText;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/access-history/role-events} → {@code kf_access_hist_role_event}: IIQ Access-History role events
 * ({@code sailpoint.object.accesshistory.HistoricalRoleEvent}). Reproduces Part 1's {@code NativeHistRoleEventRepository},
 * which is the identity-event repository with a different table, key salt and object type. The SAME 38 response
 * fields, iiq_native column layout and 17-field {@code record_hash} as
 * {@link AccessHistoryIdentityEventRepository} (whose column list and hash this class reuses), plus:
 * <ul>
 *   <li><b>Append-only.</b> {@code INSERT … ON CONFLICT (hist_event_id) DO NOTHING}: never updated, never deleted, no
 *       soft-delete sweep. Existing events are {@link EntityRepository.Outcome#SKIPPED}.</li>
 *   <li>PK {@code hist_event_id} = canonical source_id, else
 *       {@code deterministicUuid("native-hist-role-event|"+source_id)} (a null id seeds "native-hist-role-event|null").</li>
 *   <li>Lineage: {@code IdentityIQ} / {@code native_iiq_java_api} /
 *       {@code sailpoint.object.accesshistory.HistoricalRoleEvent}.</li>
 *   <li>Relationships (raw IIQ ids, kept verbatim): {@code entity_id} / {@code entity_name} = the role (Bundle);
 *       {@code capture_id} / {@code prev_capture_id} = the role-level access-history captures before/after the change.</li>
 * </ul>
 */
public final class AccessHistoryRoleEventRepository implements EntityWriter {

    public static final String RESOURCE = "access-history/role-events";
    public static final String TABLE = "kf_access_hist_role_event";
    public static final String SRC_SYSTEM = "IdentityIQ";
    public static final String SRC_INTERFACE = "native_iiq_java_api";
    public static final String SRC_OBJECT_TYPE = "sailpoint.object.accesshistory.HistoricalRoleEvent";

    private static final String EVENT_DETAIL = "event_detail_json";

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String appendSql;

    public AccessHistoryRoleEventRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n")
                .append("    hist_event_id uuid PRIMARY KEY");
        StringBuilder cols = new StringBuilder("hist_event_id");
        StringBuilder vals = new StringBuilder("?::uuid");
        for (String c : AccessHistoryIdentityEventRepository.COLUMNS) {
            boolean ts = c.equals("event_date") || c.equals("created_at") || c.equals("modified_at");
            ddl.append(",\n    ").append(c).append(ts ? " timestamp with time zone" : " text");
            cols.append(", ").append(c);
            vals.append(ts ? ", ?::timestamptz" : ", ?");
        }
        this.createTableSql = ddl.append(",\n    record_hash text")
                .append(",\n    source_system text")
                .append(",\n    source_interface text")
                .append(",\n    source_object_type text")
                .append(",\n    extraction_run_id text")
                .append(",\n    extracted_at timestamp with time zone NOT NULL DEFAULT now()")
                .append("\n)").toString();
        this.appendSql = "INSERT INTO " + table + " (" + cols
                + ", record_hash, source_system, source_interface, source_object_type, extraction_run_id) VALUES ("
                + vals + ", ?, ?, ?, ?, ?) ON CONFLICT (hist_event_id) DO NOTHING RETURNING hist_event_id";
    }

    @Override
    public String entity() {
        return "HistoricalRoleEvent";
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
            for (String c : AccessHistoryIdentityEventRepository.COLUMNS) {
                ps.setString(i++, c.equals(EVENT_DETAIL) ? jsonText(row, c) : text(row, c));
            }
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

    /** Part 1 {@code NativeHistRoleEventRepository.canonicalId}. */
    public static String id(JsonNode row) {
        String sourceId = text(row, "source_id");
        String id = CanonicalIds.canonicalUuid(sourceId);
        if (id == null) {
            id = CanonicalIds.deterministicUuid("native-hist-role-event|" + sourceId);
        }
        return id;
    }

    /** Part 1 {@code NativeHistRoleEventRepository.recordHash}: the same 17 wire keys as the identity-event hash. */
    public static String recordHash(JsonNode r) {
        return AccessHistoryIdentityEventRepository.recordHash(r);
    }

    @Override
    public String ref(JsonNode row) {
        return "access-history role event " + text(row, "event_type") + " (source_id=" + text(row, "source_id") + ")";
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

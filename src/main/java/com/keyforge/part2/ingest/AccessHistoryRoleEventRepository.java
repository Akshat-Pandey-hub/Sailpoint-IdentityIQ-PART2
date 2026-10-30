package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.SchemaName;
import com.keyforge.part2.id.CanonicalIds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.ArrayList;

import static com.keyforge.part2.hash.HashValues.jsonText;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/access-history/role-events} → {@code kf_access_hist_role_event}: IIQ Access-History role events
 * ({@code sailpoint.object.accesshistory.HistoricalRoleEvent}). Reproduces Part 1's {@code NativeHistRoleEventRepository},
 * which is the identity-event repository with a different table, key salt and object type. The SAME 38 response
 * fields, iiq_native column layout and 17-field {@code record_hash} as
 * {@link AccessHistoryIdentityEventRepository} (whose column list and hash this class reuses), plus:
 * <ul>
 *   <li><b>Kept, updated only when IIQ changed it.</b> {@code INSERT … ON CONFLICT (hist_event_id) DO UPDATE} only when a
 *       data column differs ({@link UpsertSql}): never deleted, no soft-delete sweep. Existing events that are
 *       unchanged are {@link EntityRepository.Outcome#SKIPPED}; changed ones (e.g. a flag IIQ updates later) are
 *       {@link EntityRepository.Outcome#UPDATED}.</li>
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
        List<String> compared = new ArrayList<>(AccessHistoryIdentityEventRepository.COLUMNS);
        compared.add("record_hash");
        this.appendSql = "INSERT INTO " + table + " AS " + UpsertSql.ALIAS + " (" + cols
                + ", record_hash, source_system, source_interface, source_object_type, extraction_run_id) VALUES ("
                + vals + ", ?, ?, ?, ?, ?) "
                + UpsertSql.tail("hist_event_id", compared, List.of("source_system", "source_interface", "source_object_type", "extraction_run_id"), false);
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

    /** Inserts one event; an existing one is updated only when a data column changed, else {@code SKIPPED}. */
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
                return UpsertSql.outcome(rs);
            }
        }
    }

    /** Part 1 {@code NativeHistRoleEventRepository.canonicalId}. */
    public static String id(JsonNode row) {
        if (CanonicalIds.blank(text(row, "source_id"))) {
            return CanonicalIds.contentUuid("native-hist-role-event", row); // no IIQ id: a uuid from the full content
        }
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

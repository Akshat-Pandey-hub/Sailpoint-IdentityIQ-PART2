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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.jsonText;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/access-history/identity-events} → {@code kf_access_hist_identity_event}: IIQ Access-History
 * identity events ({@code sailpoint.object.accesshistory.HistoricalIdentityEvent}). Reproduces Part 1's
 * {@code NativeHistIdentityEventRepository}:
 * <ul>
 *   <li><b>Append-only.</b> {@code INSERT … ON CONFLICT (hist_event_id) DO NOTHING}: never updated, never deleted, no
 *       soft-delete sweep. Existing events are {@link EntityRepository.Outcome#SKIPPED}.</li>
 *   <li>PK {@code hist_event_id} = canonical source_id, else {@code deterministicUuid("native-hist-event|"+source_id)}
 *       (Java concatenation, so a null id seeds "native-hist-event|null").</li>
 *   <li>38 response fields, all TEXT except {@code event_date}, {@code created_at}, {@code modified_at} (timestamps).
 *       {@code event_detail_json} is TEXT holding IIQ's serialized detail verbatim; the KF Agent re-hydrates it into
 *       JSON and compact re-serialization restores the stored text (a value the agent could not parse arrives as a
 *       string and is kept as-is).</li>
 *   <li>{@code record_hash} = Part 1's 17-field hash over the WIRE values read with {@code asText()}
 *       ({@code event_date} as its ISO text, {@code event_detail_json} as the raw serialized text).</li>
 *   <li>Lineage: {@code IdentityIQ} / {@code native_iiq_java_api} /
 *       {@code sailpoint.object.accesshistory.HistoricalIdentityEvent}.</li>
 *   <li>Relationships (raw IIQ ids, kept verbatim): {@code entity_id} / {@code entity_name} = the identity;
 *       {@code capture_id} / {@code prev_capture_id} = the access-history captures before/after the change;
 *       {@code account_id} / {@code acct_app_id} / {@code acct_app_name} = the account and its application;
 *       {@code identity_entitlement_id}, {@code request_item_id} / {@code pending_request_item_id},
 *       {@code identity_request_id}, {@code audit_event_id}.</li>
 * </ul>
 */
public final class AccessHistoryIdentityEventRepository implements EntityWriter {

    public static final String RESOURCE = "access-history/identity-events";
    public static final String TABLE = "kf_access_hist_identity_event";
    public static final String SRC_SYSTEM = "IdentityIQ";
    public static final String SRC_INTERFACE = "native_iiq_java_api";
    public static final String SRC_OBJECT_TYPE = "sailpoint.object.accesshistory.HistoricalIdentityEvent";

    private static final String EVENT_DETAIL = "event_detail_json";

    /** Response columns between hist_event_id and record_hash, in iiq_native order. */
    static final List<String> COLUMNS;

    static {
        List<String> c = new ArrayList<>(List.of(
                "source_id", "name", "entity_id", "entity_name", "defined_entity_name", "event_type",
                "event_category", "event_source_type", "event_date", EVENT_DETAIL, "prev_capture_id", "capture_id",
                "audit_event_id", "property_name", "old_value", "new_value", "account_id", "acct_app_id",
                "acct_app_name", "acct_app_instance", "acct_display_name", "acct_native_id",
                "pending_request_item_id", "request_item_id", "identity_request_id", "identity_entitlement_id"));
        for (int q = 1; q <= 10; q++) {
            c.add("qry_property" + q);
        }
        c.add("created_at");
        c.add("modified_at");
        COLUMNS = List.copyOf(c);
    }

    private static boolean isTimestamp(String col) {
        return col.equals("event_date") || col.equals("created_at") || col.equals("modified_at");
    }

    /** Part 1's hashed wire keys → the response field carrying the same value. */
    private static final Map<String, String> HASHED = new LinkedHashMap<>();

    static {
        String[][] pairs = {
                {"sourceId", "source_id"}, {"entityId", "entity_id"}, {"eventType", "event_type"},
                {"eventCategory", "event_category"}, {"eventSourceType", "event_source_type"},
                {"eventDate", "event_date"}, {"captureId", "capture_id"}, {"prevCaptureId", "prev_capture_id"},
                {"propertyName", "property_name"}, {"oldValue", "old_value"}, {"newValue", "new_value"},
                {"accountId", "account_id"}, {"acctAppId", "acct_app_id"}, {"requestItemId", "request_item_id"},
                {"identityRequestId", "identity_request_id"}, {"identityEntitlementId", "identity_entitlement_id"},
                {"eventDetailJson", EVENT_DETAIL}};
        for (String[] p : pairs) {
            HASHED.put(p[0], p[1]);
        }
    }

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String appendSql;

    public AccessHistoryIdentityEventRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n")
                .append("    hist_event_id uuid PRIMARY KEY");
        StringBuilder cols = new StringBuilder("hist_event_id");
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
        this.appendSql = "INSERT INTO " + table + " (" + cols
                + ", record_hash, source_system, source_interface, source_object_type, extraction_run_id) VALUES ("
                + vals + ", ?, ?, ?, ?, ?) ON CONFLICT (hist_event_id) DO NOTHING RETURNING hist_event_id";
    }

    @Override
    public String entity() {
        return "HistoricalIdentityEvent";
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
            for (String c : COLUMNS) {
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

    /** Part 1 {@code NativeHistIdentityEventRepository.canonicalId}. */
    public static String id(JsonNode row) {
        String sourceId = text(row, "source_id");
        String id = CanonicalIds.canonicalUuid(sourceId);
        if (id == null) {
            id = CanonicalIds.deterministicUuid("native-hist-event|" + sourceId);
        }
        return id;
    }

    /** Part 1 {@code NativeHistIdentityEventRepository.recordHash}: 17 wire keys, each read with {@code asText()}. */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : HASHED.entrySet()) {
            b.put(e.getKey(), e.getValue().equals(EVENT_DETAIL) ? jsonText(r, EVENT_DETAIL) : text(r, e.getValue()));
        }
        return RecordHash.of(b);
    }

    @Override
    public String ref(JsonNode row) {
        return "access-history identity event " + text(row, "event_type") + " (source_id=" + text(row, "source_id") + ")";
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

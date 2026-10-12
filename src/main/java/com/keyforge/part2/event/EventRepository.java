package com.keyforge.part2.event;

import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.ZoneOffset;

/**
 * Plain-JDBC access for the append-only {@code <schema>.kf_event} store: Part 1's {@code iiq.event.EventRepository}
 * DDL (column for column) and insert. Writes are strictly {@code INSERT … ON CONFLICT (event_id) DO NOTHING}: an event
 * is never updated or deleted, and re-deriving the same source fact is a no-op. {@code raw_ref} stays NULL (no RAW
 * zone), as in Part 1.
 */
public final class EventRepository {

    public static final String TABLE = "kf_event";

    private final String schema;
    private final String table;

    public EventRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
    }

    public String table() {
        return table;
    }

    public String createTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "event_id uuid PRIMARY KEY, "
                + "src_system text, "
                + "src_object_type text, "
                + "src_object_id text, "
                + "event_type text, "
                + "event_fingerprint text, "
                + "src_event_ts timestamptz, "
                + "src_event_ts_precision text, "
                + "extraction_run_id text, "
                + "src_interface text, "
                + "raw_ref text, "
                + "event_detail jsonb, "
                + "extracted_at timestamptz NOT NULL DEFAULT now())";
    }

    public String appendSql() {
        return "INSERT INTO " + table + " (event_id, src_system, src_object_type, src_object_id, event_type, "
                + "event_fingerprint, src_event_ts, src_event_ts_precision, extraction_run_id, src_interface, "
                + "raw_ref, event_detail) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?::jsonb) "
                + "ON CONFLICT (event_id) DO NOTHING "
                + "RETURNING event_id";
    }

    public void ensureTargetTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql());
        }
    }

    /** Appends one event; {@code true} if it was new, {@code false} if that event_id already existed. */
    public boolean append(Connection conn, EventRow row) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(appendSql())) {
            int i = 1;
            ps.setString(i++, row.eventId());
            ps.setString(i++, row.srcSystem());
            ps.setString(i++, row.srcObjectType());
            ps.setString(i++, row.srcObjectId());
            ps.setString(i++, row.eventType());
            ps.setString(i++, row.eventFingerprint());
            if (row.srcEventTs() == null) {
                ps.setNull(i++, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setObject(i++, row.srcEventTs().atOffset(ZoneOffset.UTC));
            }
            ps.setString(i++, row.srcEventTsPrecision());
            ps.setString(i++, row.extractionRunId());
            ps.setString(i++, row.srcInterface());
            ps.setString(i, row.eventDetailJson());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}

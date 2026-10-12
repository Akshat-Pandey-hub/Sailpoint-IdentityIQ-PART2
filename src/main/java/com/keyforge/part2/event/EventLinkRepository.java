package com.keyforge.part2.event;

import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain-JDBC, append-only persistence for the native {@code <schema>.kf_event_link} table: Part 1's
 * {@code iiq.nativeevent.NativeEventLinkRepository} DDL (column for column, as in the reference dump: {@code link_id}
 * primary key, no foreign key, no {@code audit_event_id}) and insert. Writes are strictly
 * {@code INSERT … ON CONFLICT (link_id) DO NOTHING}: no UPDATE, no DELETE.
 *
 * <p>Part 1 also has a legacy REST-pipeline {@code kf_event_link} ({@code id}, {@code audit_event_id NOT NULL}, ...)
 * in its {@code migration_test} schema. That shape is never created or altered here: if the target table already
 * exists with a non-native shape, {@link #checkCompatible} refuses to write (nothing is dropped, truncated or altered).
 */
public final class EventLinkRepository {

    public static final String TABLE = "kf_event_link";

    /** The native columns, in Part 1 / dump order. */
    static final List<String> NATIVE_COLUMNS = List.of("link_id", "event_id", "src_object_type", "src_object_id",
            "target_object_type", "target_object_id", "link_type", "link_status", "extraction_run_id",
            "extracted_at");

    private final String schema;
    private final String table;

    public EventLinkRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
    }

    public String table() {
        return table;
    }

    public String createTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "link_id uuid PRIMARY KEY, "
                + "event_id uuid, "
                + "src_object_type text, "
                + "src_object_id text, "
                + "target_object_type text, "
                + "target_object_id text, "
                + "link_type text, "
                + "link_status text, "
                + "extraction_run_id text, "
                + "extracted_at timestamptz NOT NULL DEFAULT now())";
    }

    public String appendSql() {
        return "INSERT INTO " + table + " (link_id, event_id, src_object_type, src_object_id, "
                + "target_object_type, target_object_id, link_type, link_status, extraction_run_id) "
                + "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (link_id) DO NOTHING RETURNING link_id";
    }

    /** Every existing column of the target table: name → "NOT NULL without default" flag (empty if absent). */
    static String columnsSql() {
        return "SELECT column_name, (is_nullable = 'NO' AND column_default IS NULL) AS required "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
    }

    /**
     * Refuses an existing {@code kf_event_link} that is not the native shape (e.g. the legacy
     * {@code id} / {@code audit_event_id} table): every native column must exist, and no other column may be
     * NOT NULL without a default (our insert would violate it). An absent table is fine (it is created).
     */
    public void checkCompatible(Connection conn) throws SQLException {
        Map<String, Boolean> existing = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(columnsSql())) {
            ps.setString(1, schema);
            ps.setString(2, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existing.put(rs.getString(1), rs.getBoolean(2));
                }
            }
        }
        String problem = incompatibility(existing);
        if (problem != null) {
            throw new IncompatibleLinkTableException(table + " exists but is not the native event-link table ("
                    + problem + "). Nothing was written, altered or dropped. Point PG_DEST_SCHEMA at a schema "
                    + "without it, or rename/migrate that table yourself.");
        }
    }

    /** Pure: why an existing column set is not the native shape, or {@code null} when compatible / absent. */
    static String incompatibility(Map<String, Boolean> existing) {
        if (existing.isEmpty()) {
            return null;
        }
        List<String> missing = new ArrayList<>();
        for (String c : NATIVE_COLUMNS) {
            if (!existing.containsKey(c)) {
                missing.add(c);
            }
        }
        List<String> requiredForeign = new ArrayList<>();
        existing.forEach((c, required) -> {
            if (required && !NATIVE_COLUMNS.contains(c)) {
                requiredForeign.add(c);
            }
        });
        if (missing.isEmpty() && requiredForeign.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (!missing.isEmpty()) {
            parts.add("missing native column(s) " + missing);
        }
        if (!requiredForeign.isEmpty()) {
            parts.add("non-native NOT NULL column(s) " + requiredForeign);
        }
        return String.join("; ", parts);
    }

    public void ensureTargetTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql());
        }
    }

    /** The event ids currently stored in {@code kf_event} (the caller checks that the table exists). */
    public Set<String> existingEventIds(Connection conn) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT event_id::text FROM " + schema + "." + EventRepository.TABLE)) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    /** Appends one link; {@code true} if new, {@code false} if that link_id already existed. */
    public boolean append(Connection conn, EventLinkRow row) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(appendSql())) {
            int i = 1;
            ps.setString(i++, row.linkId());
            ps.setString(i++, row.eventId());
            ps.setString(i++, row.srcObjectType());
            ps.setString(i++, row.srcObjectId());
            ps.setString(i++, row.targetObjectType());
            ps.setString(i++, row.targetObjectId());
            ps.setString(i++, row.linkType());
            ps.setString(i++, row.linkStatus());
            ps.setString(i, row.extractionRunId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** An existing kf_event_link with a non-native shape: nothing is written. */
    public static final class IncompatibleLinkTableException extends SQLException {
        public IncompatibleLinkTableException(String message) {
            super(message);
        }
    }
}

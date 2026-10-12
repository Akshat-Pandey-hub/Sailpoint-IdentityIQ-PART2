package com.keyforge.part2.incremental;

import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Plain-JDBC access for {@code <schema>.kf_extraction_watermark}: Part 1's exact table (one row per entity key, the
 * newest source change time captured, the run that set it). Timestamps are bound as UTC {@link OffsetDateTime} and
 * read back as {@link OffsetDateTime}, so the stored instant does not depend on the session time zone.
 *
 * <p>Unlike Part 1's unconditional upsert, {@link #advance} only ever moves a watermark FORWARD: the conflict update
 * applies only when the new value is later than the stored one, so a slower concurrent run or a repeated run can never
 * move it backwards (and the stored {@code last_run_id} stays the run that set the current value).
 */
public final class WatermarkRepository {

    public static final String TABLE = "kf_extraction_watermark";

    private final String schema;
    private final String table;

    public WatermarkRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
    }

    public String targetTable() {
        return table;
    }

    public String createTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "entity text PRIMARY KEY, "
                + "watermark_field text, "
                + "source_watermark timestamptz, "
                + "last_run_id uuid, "
                + "updated_at timestamptz NOT NULL DEFAULT now())";
    }

    public String advanceSql() {
        return "INSERT INTO " + table + " (entity, watermark_field, source_watermark, last_run_id, updated_at) "
                + "VALUES (?,?,?,?::uuid, now()) "
                + "ON CONFLICT (entity) DO UPDATE SET watermark_field = EXCLUDED.watermark_field, "
                + "source_watermark = EXCLUDED.source_watermark, last_run_id = EXCLUDED.last_run_id, updated_at = now() "
                + "WHERE " + table + ".source_watermark IS NULL "
                + "OR EXCLUDED.source_watermark > " + table + ".source_watermark";
    }

    public void ensureTargetTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql());
        }
    }

    /** The stored watermark for an entity key, or {@code null} when it has never been set. */
    public Instant read(Connection conn, String entity) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT source_watermark FROM " + table + " WHERE entity = ?")) {
            ps.setString(1, entity);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                OffsetDateTime odt = rs.getObject(1, OffsetDateTime.class);
                return odt == null ? null : odt.toInstant();
            }
        }
    }

    /**
     * Sets the watermark if absent, or moves it forward to {@code sourceWatermark}. Returns {@code true} if a row was
     * written, {@code false} if the stored watermark was already at or past the new value.
     */
    public boolean advance(Connection conn, String entity, String watermarkField, Instant sourceWatermark,
                           String runId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(advanceSql())) {
            ps.setString(1, entity);
            ps.setString(2, watermarkField);
            ps.setObject(3, sourceWatermark.atOffset(ZoneOffset.UTC));
            ps.setString(4, runId);
            return ps.executeUpdate() > 0;
        }
    }
}

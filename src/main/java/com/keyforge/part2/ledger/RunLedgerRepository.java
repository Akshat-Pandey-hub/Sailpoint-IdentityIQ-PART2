package com.keyforge.part2.ledger;

import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

/**
 * Plain-JDBC access for {@code <schema>.kf_extraction_run}: one row per run (the id is a fresh UUID per run). The row
 * is written when the run starts (status RUNNING) and written again when it finishes: the second write updates the
 * same row ({@code ON CONFLICT DO UPDATE}). {@code request} holds the caller's fetch scope, or NULL for a full run.
 */
public final class RunLedgerRepository {

    public static final String TABLE = "kf_extraction_run";

    private final String schema;
    private final String table;

    public RunLedgerRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
    }

    public String targetTable() {
        return table;
    }

    public String createTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "extraction_run_id uuid PRIMARY KEY, "
                + "command text NOT NULL, "
                + "source_interface text, "
                + "status text NOT NULL, "
                + "started_at timestamptz NOT NULL, "
                + "ended_at timestamptz, "
                + "duration_ms bigint, "
                + "window_start timestamptz, "
                + "window_end timestamptz, "
                + "extracted integer, "
                + "inserted integer, "
                + "updated integer, "
                + "failed integer, "
                + "entity_counts jsonb, "
                + "error_message text, "
                + "recorded_at timestamptz NOT NULL DEFAULT now(), "
                + "request jsonb)";
    }

    public String insertSql() {
        return "INSERT INTO " + table + " (extraction_run_id, command, source_interface, status, "
                + "started_at, ended_at, duration_ms, window_start, window_end, extracted, inserted, updated, "
                + "failed, entity_counts, error_message, request) "
                + "VALUES (?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb) "
                + "ON CONFLICT (extraction_run_id) DO UPDATE SET status = EXCLUDED.status, "
                + "ended_at = EXCLUDED.ended_at, duration_ms = EXCLUDED.duration_ms, "
                + "window_start = EXCLUDED.window_start, window_end = EXCLUDED.window_end, "
                + "extracted = EXCLUDED.extracted, inserted = EXCLUDED.inserted, updated = EXCLUDED.updated, "
                + "failed = EXCLUDED.failed, entity_counts = EXCLUDED.entity_counts, "
                + "error_message = EXCLUDED.error_message, request = EXCLUDED.request, recorded_at = now()";
    }

    public void ensureTargetTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql());
            st.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS request jsonb");
        }
    }

    public void insert(Connection conn, ExtractionRun r) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(insertSql())) {
            int i = 1;
            ps.setString(i++, r.extractionRunId());
            ps.setString(i++, r.command());
            ps.setString(i++, r.sourceInterface());
            ps.setString(i++, r.status());
            setTs(ps, i++, r.startedAt());
            setTs(ps, i++, r.endedAt());
            setLong(ps, i++, r.durationMs());
            setTs(ps, i++, r.windowStart());
            setTs(ps, i++, r.windowEnd());
            setInt(ps, i++, r.extracted());
            setInt(ps, i++, r.inserted());
            setInt(ps, i++, r.updated());
            setInt(ps, i++, r.failed());
            ps.setString(i++, r.entityCountsJson());
            ps.setString(i++, r.errorMessage());
            ps.setString(i, r.requestJson());
            ps.executeUpdate();
        }
    }

    private static void setTs(PreparedStatement ps, int i, Instant v) throws SQLException {
        if (v == null) ps.setNull(i, Types.TIMESTAMP); else ps.setTimestamp(i, Timestamp.from(v));
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT); else ps.setLong(i, v);
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER); else ps.setInt(i, v);
    }
}

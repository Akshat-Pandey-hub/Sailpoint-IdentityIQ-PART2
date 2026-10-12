package com.keyforge.part2.reconciliation;

import com.keyforge.part2.config.SchemaName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Plain-JDBC access for {@code <schema>.kf_reconciliation_finding}, reproducing Part 1's shared findings table
 * ({@code iiq.reconciliation.ReconciliationRepository} DDL + the {@code note} column {@code WorkItemReconciliation}
 * adds; column order as in iiq_native). Reconciliation reads the destination tables and writes ONLY this table.
 *
 * <p>Lifecycle (Part 1): one finding per (run, check). {@code finding_id = UUID.nameUUIDFromBytes(runId|checkName)},
 * so re-running a check within the same run updates its finding in place, and each new run adds its own dated
 * finding — the table is a per-run history of each check, not a deduplicated issue list.
 */
public final class ReconciliationFindingRepository {

    public static final String TABLE = "kf_reconciliation_finding";

    /** One finding row (Part 1 columns). {@code orphanCount} {@code null} = not evaluated (SKIPPED). */
    public record Finding(String checkName, String childTable, String childColumn, String parentTable,
                          String parentColumn, String status, String skipReason, Long orphanCount,
                          List<String> sampleIds, String severity, String note) { }

    private final String schema;
    private final String table;

    public ReconciliationFindingRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
    }

    public String schema() {
        return schema;
    }

    public String targetTable() {
        return table;
    }

    public String createTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "finding_id uuid PRIMARY KEY, "
                + "run_id text, "
                + "check_name text, "
                + "child_table text, child_column text, "
                + "parent_table text, parent_column text, "
                + "status text, skip_reason text, "
                + "orphan_count bigint, "
                + "sample_ids jsonb, "
                + "severity text, "
                + "detected_at timestamptz, "
                + "note text)";
    }

    public String upsertSql() {
        return "INSERT INTO " + table + " (finding_id, run_id, check_name, child_table, child_column, "
                + "parent_table, parent_column, status, skip_reason, orphan_count, sample_ids, severity, note, detected_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?, CAST(? AS jsonb), ?, ?, ?) "
                + "ON CONFLICT (finding_id) DO UPDATE SET run_id=EXCLUDED.run_id, status=EXCLUDED.status, "
                + "skip_reason=EXCLUDED.skip_reason, orphan_count=EXCLUDED.orphan_count, "
                + "sample_ids=EXCLUDED.sample_ids, severity=EXCLUDED.severity, note=EXCLUDED.note, "
                + "detected_at=EXCLUDED.detected_at";
    }

    /** Creates the schema and table if absent; adds {@code note} to a table created without it (as Part 1 does). */
    public void ensureTargetTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql());
            st.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS note text");
        }
    }

    /** Part 1's deterministic finding id per (runId, checkName). */
    public static UUID findingId(String runId, String checkName) {
        return UUID.nameUUIDFromBytes((runId + "|" + checkName).getBytes(StandardCharsets.UTF_8));
    }

    /** Inserts the run's finding for a check, or updates it if this run already recorded it. */
    public UUID upsert(Connection conn, String runId, Finding f, Instant detectedAt) throws SQLException {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("a reconciliation finding needs the extraction run id");
        }
        UUID id = findingId(runId, f.checkName());
        try (PreparedStatement ps = conn.prepareStatement(upsertSql())) {
            ps.setObject(1, id);
            ps.setString(2, runId);
            ps.setString(3, f.checkName());
            ps.setString(4, f.childTable());
            ps.setString(5, f.childColumn());
            ps.setString(6, f.parentTable());
            ps.setString(7, f.parentColumn());
            ps.setString(8, f.status());
            ps.setString(9, f.skipReason());
            if (f.orphanCount() == null) {
                ps.setNull(10, Types.BIGINT);
            } else {
                ps.setLong(10, f.orphanCount());
            }
            ps.setString(11, sampleJson(f.sampleIds()));
            ps.setString(12, f.severity());
            ps.setString(13, f.note());
            ps.setTimestamp(14, Timestamp.from(detectedAt));
            ps.executeUpdate();
        }
        return id;
    }

    /** Part 1's JSON array of sample ids (a null id becomes "", quotes escaped). */
    static String sampleJson(List<String> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(ids.get(i) == null ? "" : ids.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}

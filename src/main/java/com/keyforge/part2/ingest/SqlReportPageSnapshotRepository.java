package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/**
 * Append-only storage for caller-requested pages of SQL reports. Rows are retained as JSON because report projections
 * have no verified stable source key; no canonical report row is updated, replaced, or deleted by this repository.
 */
public final class SqlReportPageSnapshotRepository {

    public static final String SNAPSHOT_TABLE = "kf_sql_report_page_snapshot";
    public static final String ROW_TABLE = "kf_sql_report_page_snapshot_row";

    private final String schema;
    private final String snapshotTable;
    private final String rowTable;

    public SqlReportPageSnapshotRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.snapshotTable = this.schema + "." + SNAPSHOT_TABLE;
        this.rowTable = this.schema + "." + ROW_TABLE;
    }

    public String snapshotTable() {
        return snapshotTable;
    }

    public String rowTable() {
        return rowTable;
    }

    /** Additive, idempotent DDL; existing canonical report tables and data are untouched. */
    public void ensureSchemaAndTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute("CREATE TABLE IF NOT EXISTS " + snapshotTable + " ("
                    + "snapshot_id uuid PRIMARY KEY, "
                    + "report_name text NOT NULL, "
                    + "report_resource text NOT NULL, "
                    + "extraction_run_id text NOT NULL, "
                    + "requested_start integer NOT NULL CHECK (requested_start >= 0), "
                    + "requested_limit integer NOT NULL CHECK (requested_limit > 0), "
                    + "fetched_at timestamp with time zone NOT NULL DEFAULT now(), "
                    + "row_count integer NOT NULL CHECK (row_count >= 0))");
            st.execute("CREATE TABLE IF NOT EXISTS " + rowTable + " ("
                    + "snapshot_id uuid NOT NULL REFERENCES " + snapshotTable + "(snapshot_id), "
                    + "row_ordinal integer NOT NULL CHECK (row_ordinal >= 0), "
                    + "row_json jsonb NOT NULL, "
                    + "PRIMARY KEY (snapshot_id, row_ordinal))");
            // PostgreSQL derives the index schema from the qualified target table; its index name must be unqualified.
            st.execute("CREATE INDEX IF NOT EXISTS " + SNAPSHOT_TABLE
                    + "_resource_page_idx ON " + snapshotTable
                    + " (report_resource, requested_start, requested_limit, fetched_at)");
            st.execute("CREATE INDEX IF NOT EXISTS " + SNAPSHOT_TABLE
                    + "_run_idx ON " + snapshotTable + " (extraction_run_id)");
        }
    }

    public record StoredSnapshot(UUID id, int rowsStored) { }

    /** Persist one invocation's header and all returned rows atomically; repeated calls always get a new snapshot id. */
    public StoredSnapshot store(Connection conn, String reportName, String resource, String extractionRunId,
                                int start, int limit, List<JsonNode> rows) throws SQLException {
        if (start < 0 || limit <= 0 || start > Integer.MAX_VALUE - limit) {
            throw new IllegalArgumentException("invalid SQL report page window");
        }
        UUID snapshotId = UUID.randomUUID();
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            String headerSql = "INSERT INTO " + snapshotTable + " (snapshot_id, report_name, report_resource, "
                    + "extraction_run_id, requested_start, requested_limit, row_count) "
                    + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement header = conn.prepareStatement(headerSql)) {
                header.setString(1, snapshotId.toString());
                header.setString(2, reportName);
                header.setString(3, resource);
                header.setString(4, extractionRunId);
                header.setInt(5, start);
                header.setInt(6, limit);
                header.setInt(7, rows.size());
                header.executeUpdate();
            }

            if (!rows.isEmpty()) {
                String rowSql = "INSERT INTO " + rowTable + " (snapshot_id, row_ordinal, row_json) "
                        + "VALUES (?::uuid, ?, ?::jsonb)";
                try (PreparedStatement rowStatement = conn.prepareStatement(rowSql)) {
                    for (int rowNumber = 0; rowNumber < rows.size(); rowNumber++) {
                        rowStatement.setString(1, snapshotId.toString());
                        rowStatement.setInt(2, rowNumber);
                        rowStatement.setString(3, rows.get(rowNumber).toString());
                        rowStatement.addBatch();
                    }
                    rowStatement.executeBatch();
                }
            }
            conn.commit();
            return new StoredSnapshot(snapshotId, rows.size());
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }
}

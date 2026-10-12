package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Persistence for Part 1's SQL-REPORT tables (e.g. {@code kf_entitlement_assignment}), reproducing
 * {@code NativeSqlReportRepository}. Such a table is the stored RESULT of a baked business SQL query, not an object
 * model, so it differs from every entity table:
 * <ul>
 *   <li>Columns = the query's SELECT aliases, all {@code text}; values stored verbatim (JSON {@code null} → SQL NULL).</li>
 *   <li>PK = {@code id bigint GENERATED ALWAYS AS IDENTITY} — a database row number, NOT derived from the data. There is
 *       no {@code record_hash}, no {@code source_object_type} and no soft-delete columns.</li>
 *   <li>Lineage: {@code source_system = IdentityIQ}, {@code source_interface = native_iiq_jdbc} (Part 1 marks the
 *       SQL-result path this way, distinct from {@code native_iiq_java_api}), {@code extraction_run_id},
 *       {@code extracted_at}.</li>
 *   <li>Load = REPLACE-ALL in one transaction: {@code DELETE} every row, then batch-{@code INSERT} the latest result, so
 *       the table always equals the latest query result and a re-run is idempotent. Any failure rolls back, leaving the
 *       previous contents intact.</li>
 * </ul>
 */
public final class SqlReportRepository {

    private final String schema;
    private final String table;
    private final List<String> columns;
    private final String createTableSql;
    private final String insertSql;

    public SqlReportRepository(String schema, String table, List<String> columns) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + table;
        this.columns = List.copyOf(columns);
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(this.table).append(" (\n")
                .append("    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY");
        for (String c : this.columns) {
            ddl.append(",\n    ").append(c).append(" text");
        }
        ddl.append(",\n    source_system text")
                .append(",\n    source_interface text")
                .append(",\n    extraction_run_id text")
                .append(",\n    extracted_at timestamp with time zone NOT NULL DEFAULT now()")
                .append("\n)");
        this.createTableSql = ddl.toString();
        StringBuilder cols = new StringBuilder();
        StringBuilder qs = new StringBuilder();
        for (String c : this.columns) {
            cols.append(c).append(", ");
            qs.append("?, ");
        }
        this.insertSql = "INSERT INTO " + this.table + " (" + cols + "source_system, source_interface, extraction_run_id) "
                + "VALUES (" + qs + "?, ?, ?)";
    }

    public String targetTable() {
        return table;
    }

    public void ensureSchemaAndTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql);
        }
    }

    /** Result of one replace: rows removed (previous run) and rows written (this run). */
    public record Replaced(int deleted, int inserted) { }

    /**
     * Replaces the whole table with {@code rows} in ONE transaction (DELETE + batch INSERT, as Part 1). Each row binds
     * exactly this report's columns in order; a missing or JSON-null value is SQL NULL, any other value its text.
     */
    public Replaced replaceAll(Connection conn, List<JsonNode> rows, String extractionRunId) throws SQLException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int deleted;
            try (Statement del = conn.createStatement()) {
                deleted = del.executeUpdate("DELETE FROM " + table);
            }
            int inserted = 0;
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (JsonNode row : rows) {
                    int i = 1;
                    for (String c : columns) {
                        JsonNode v = row.get(c);
                        ps.setString(i++, (v == null || v.isNull()) ? null : v.asText());
                    }
                    ps.setString(i++, "IdentityIQ");
                    ps.setString(i++, "native_iiq_jdbc");
                    ps.setString(i, extractionRunId);
                    ps.addBatch();
                    inserted++;
                    if (inserted % 1000 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            conn.commit();
            return new Replaced(deleted, inserted);
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    /** Exposed for tests. */
    public String createTableSql() {
        return createTableSql;
    }

    /** Exposed for tests. */
    public String insertSql() {
        return insertSql;
    }
}

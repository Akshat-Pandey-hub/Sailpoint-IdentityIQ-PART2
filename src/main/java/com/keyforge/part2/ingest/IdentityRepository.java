package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.SchemaName;
import com.keyforge.part2.id.CanonicalIds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Plain-JDBC persistence for {@code <schema>.kf_identity}, reproducing Part 1's identity semantics against a
 * configurable DESTINATION schema: {@code userid} (uuid) PK = {@code canonicalUuid(source_id)} (with the
 * deterministic fallback), {@code INSERT … ON CONFLICT (userid) DO UPDATE}, the 7 generated source-metadata
 * columns, and a business {@code record_hash} used to skip unchanged writes. Self-creating schema+table. All business values are bound from the
 * JSON row with parameterized SQL and per-kind casts; the schema identifier is validated (never a bound
 * param). Idempotent: a re-run updates in place, the {@code userid} never changes.
 */
public final class IdentityRepository {

    public enum Outcome { INSERTED, UPDATED, SKIPPED }

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String upsertSql;

    public IdentityRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + IdentityTable.TABLE;
        this.createTableSql = buildCreateTable();
        this.upsertSql = buildUpsert();
    }

    public String targetTable() {
        return table;
    }

    /** Create or safely migrate the destination identity table. */
    public void ensureSchemaAndTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql);
            st.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS record_hash text");
        }
    }

    /** Upsert one identity row; a no-row RETURNING result means the existing row was unchanged and is skipped. */
    public Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        String sourceId = text(row, "source_id");
        String name = text(row, "name");
        String userid = CanonicalIds.identityUserid(sourceId, name);

        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            int i = 1;
            ps.setString(i++, userid); // ?::uuid
            for (ColumnSpec c : IdentityTable.BUSINESS) {
                bind(ps, i++, c, row.get(c.name));
            }
            ps.setString(i++, BusinessHash.allColumns(row, IdentityTable.BUSINESS));
            ps.setString(i++, IdentityTable.SOURCE_SYSTEM);
            ps.setString(i++, IdentityTable.SOURCE_INTERFACE);
            ps.setString(i++, IdentityTable.SOURCE_OBJECT_TYPE);
            ps.setString(i, extractionRunId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Outcome.SKIPPED;
                return rs.getBoolean(1) ? Outcome.INSERTED : Outcome.UPDATED;
            }
        }
    }

    /** Bind one business value from its JSON node according to the column kind. Absent/null → SQL NULL. */
    private static void bind(PreparedStatement ps, int index, ColumnSpec c, JsonNode node) throws SQLException {
        boolean isNull = node == null || node.isNull();
        switch (c.kind) {
            case TEXT -> {
                if (isNull) {
                    ps.setNull(index, Types.VARCHAR);
                } else {
                    // a scalar as its text; a structured value as its JSON (asText() would lose it as "")
                    ps.setString(index, node.isValueNode() ? node.asText() : node.toString());
                }
            }
            case BOOLEAN -> {
                if (isNull) {
                    ps.setNull(index, Types.BOOLEAN);
                } else if (node.isBoolean()) {
                    ps.setBoolean(index, node.booleanValue());
                } else {
                    // tolerate a textual "true"/"false"; anything else → NULL rather than a wrong value
                    String s = node.asText();
                    if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
                        ps.setBoolean(index, Boolean.parseBoolean(s));
                    } else {
                        ps.setNull(index, Types.BOOLEAN);
                    }
                }
            }
            case JSONB -> {
                // ?::jsonb — bind the subtree verbatim as a JSON string; null varchar casts to SQL NULL.
                if (isNull) {
                    ps.setNull(index, Types.VARCHAR);
                } else {
                    ps.setString(index, node.toString());
                }
            }
            case TIMESTAMPTZ -> {
                // ?::timestamptz — bind the ISO-8601 string; PostgreSQL parses it. Null → SQL NULL.
                if (isNull) {
                    ps.setNull(index, Types.VARCHAR);
                } else {
                    ps.setString(index, node.asText());
                }
            }
            default -> throw new IllegalStateException("unhandled column kind: " + c.kind);
        }
    }

    private static String text(JsonNode row, String key) {
        JsonNode n = row == null ? null : row.get(key);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    // ── SQL builders (from the column spec, so later entities can reuse the pattern) ──

    private String buildCreateTable() {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n");
        sb.append("    ").append(IdentityTable.PK).append(" uuid PRIMARY KEY");
        for (ColumnSpec c : IdentityTable.BUSINESS) {
            sb.append(",\n    ").append(c.name).append(' ').append(c.kind.ddlType);
        }
        sb.append(",\n    record_hash text");
        sb.append(",\n    source_system text");
        sb.append(",\n    source_interface text");
        sb.append(",\n    source_object_type text");
        sb.append(",\n    extraction_run_id text");
        sb.append(",\n    extracted_at timestamp with time zone NOT NULL DEFAULT now()");
        sb.append(",\n    is_deleted boolean NOT NULL DEFAULT false");
        sb.append(",\n    deleted_at timestamp with time zone");
        sb.append("\n)");
        return sb.toString();
    }

    private String buildUpsert() {
        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        cols.add(IdentityTable.PK);
        vals.add("?::uuid");
        for (ColumnSpec c : IdentityTable.BUSINESS) {
            cols.add(c.name);
            vals.add(c.kind.placeholder);
        }
        cols.add("record_hash"); vals.add("?");
        for (String meta : List.of("source_system", "source_interface", "source_object_type", "extraction_run_id")) {
            cols.add(meta);
            vals.add("?");
        }
        // generated-at-write literals (not parameters)
        cols.add("extracted_at"); vals.add("now()");
        cols.add("is_deleted");  vals.add("false");
        cols.add("deleted_at");  vals.add("null");

        StringJoiner setClause = new StringJoiner(", ");
        for (ColumnSpec c : IdentityTable.BUSINESS) {
            setClause.add(c.name + " = EXCLUDED." + c.name);
        }
        setClause.add("record_hash = EXCLUDED.record_hash");
        for (String meta : List.of("source_system", "source_interface", "source_object_type", "extraction_run_id")) {
            setClause.add(meta + " = EXCLUDED." + meta);
        }
        setClause.add("extracted_at = now()");
        setClause.add("is_deleted = false");
        setClause.add("deleted_at = null");

        return "INSERT INTO " + table + " AS identity_row (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", vals) + ") "
                + "ON CONFLICT (" + IdentityTable.PK + ") DO UPDATE SET " + setClause
                + " WHERE identity_row.record_hash IS DISTINCT FROM EXCLUDED.record_hash OR identity_row.is_deleted"
                + " RETURNING (xmax = 0)";
    }

    /** Exposed for tests: the generated upsert SQL. */
    public String upsertSql() {
        return upsertSql;
    }

    /** Exposed for tests: the generated CREATE TABLE SQL. */
    public String createTableSql() {
        return createTableSql;
    }
}

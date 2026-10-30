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
import java.util.StringJoiner;

/**
 * Persistence for a Part 1 SQL-report table (e.g. {@code kf_entitlement_assignment}) as a keyed current-state table,
 * with the same row identity and lineage rules as the entity tables:
 * <ul>
 *   <li>PK {@code <pkColumn> uuid} = {@code deterministicUuid("native-sql-report|<reportId>|" + src_natural_key)}, where
 *       {@code src_natural_key} is the length-prefixed join of the report's key columns. The report returns no row id,
 *       so the identifying columns stand in for it: the same report row always gets the same uuid.</li>
 *   <li>Columns = the query's SELECT aliases, all {@code text}; values stored verbatim (JSON {@code null} → SQL NULL).</li>
 *   <li>{@code record_hash} = SHA-256 over every report column. Write = {@code INSERT … ON CONFLICT (pk) DO UPDATE}
 *       only when the hash changed or the row was soft-deleted: a new row is INSERTED, a changed one UPDATED, an
 *       unchanged live one SKIPPED (its lineage is left as it was).</li>
 *   <li>Lineage: {@code source_system = IdentityIQ}, {@code source_interface = native_iiq_jdbc} (Part 1's mark for the
 *       SQL-result path), {@code source_object_type = sql_report.<reportId>}, {@code src_natural_key},
 *       {@code extraction_run_id}, {@code extracted_at}, {@code is_deleted}, {@code deleted_at}.</li>
 * </ul>
 * A table created by the earlier row-numbered layout ({@code id bigint} key, no uuid key) is renamed to
 * {@code <table>_legacy} (kept, never dropped) and the keyed table is created in its place.
 */
public final class SqlReportRepository implements EntityWriter {

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_jdbc";

    private final SqlReportSpec spec;
    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String upsertSql;

    public SqlReportRepository(String schema, SqlReportSpec spec) {
        this.spec = spec;
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + spec.table();
        this.createTableSql = buildCreateTable();
        this.upsertSql = buildUpsert();
    }

    public SqlReportSpec spec() {
        return spec;
    }

    @Override
    public String entity() {
        return spec.entity();
    }

    @Override
    public String resource() {
        return spec.resource();
    }

    @Override
    public String targetTable() {
        return table;
    }

    /** Creates the schema and keyed table; an earlier row-numbered table is first renamed to {@code <table>_legacy}. */
    @Override
    public void ensureSchemaAndTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        }
        if (hasLegacyLayout(conn)) {
            String legacy = spec.table() + "_legacy";
            if (tableExists(conn, legacy)) {
                legacy = spec.table() + "_legacy_" + System.currentTimeMillis();
            }
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + table + " RENAME TO " + legacy);
            }
            System.out.println("  migrated:  " + table + " used the old row-numbered layout; renamed to "
                    + schema + "." + legacy + " (kept) and recreated with a uuid row key");
        }
        try (Statement st = conn.createStatement()) {
            st.execute(createTableSql);
        }
    }

    /** True when the table exists but has no {@code <pkColumn>} (the earlier {@code id bigint} layout). */
    private boolean hasLegacyLayout(Connection conn) throws SQLException {
        if (!tableExists(conn, spec.table())) {
            return false;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, spec.table());
            ps.setString(3, spec.pkColumn());
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private boolean tableExists(Connection conn, String unqualified) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, unqualified);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The row's uuid: derived from the report id and the key columns, never random. */
    @Override
    public String key(JsonNode row) {
        return CanonicalIds.deterministicUuid("native-sql-report|" + spec.reportId() + "|" + naturalKey(row));
    }

    /**
     * The key columns joined unambiguously: each value as {@code <length>:<value>} (a null as {@code -}), separated by
     * {@code |}, so a {@code |} inside a value can never make two different rows share a key.
     */
    public String naturalKey(JsonNode row) {
        StringJoiner key = new StringJoiner("|");
        for (String c : spec.keyColumns()) {
            String v = text(row, c);
            key.add(v == null ? "-" : v.length() + ":" + v);
        }
        return key.toString();
    }

    /** SHA-256 over every report column (text values, nulls included). */
    public String recordHash(JsonNode row) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String c : spec.columns()) {
            values.put(c, text(row, c));
        }
        return RecordHash.of(values);
    }

    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            int i = 1;
            ps.setString(i++, key(row)); // ?::uuid
            for (String c : spec.columns()) {
                ps.setString(i++, text(row, c));
            }
            ps.setString(i++, recordHash(row));
            ps.setString(i++, SOURCE_SYSTEM);
            ps.setString(i++, SOURCE_INTERFACE);
            ps.setString(i++, spec.sourceObjectType());
            ps.setString(i++, naturalKey(row));
            ps.setString(i, extractionRunId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return EntityRepository.Outcome.SKIPPED; // same values, live row: nothing written
                }
                return rs.getBoolean(1) ? EntityRepository.Outcome.INSERTED : EntityRepository.Outcome.UPDATED;
            }
        }
    }

    @Override
    public String ref(JsonNode row) {
        String k = naturalKey(row);
        return spec.entity() + " row [" + (k.length() <= 200 ? k : k.substring(0, 200) + "…") + "]";
    }

    /** JSON null / absent → null; a scalar → its text; a structured value → its compact JSON. */
    private static String text(JsonNode row, String field) {
        JsonNode n = row == null ? null : row.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.isValueNode() ? n.asText() : n.toString();
    }

    // ── SQL builders ──

    private String buildCreateTable() {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n");
        sb.append("    ").append(spec.pkColumn()).append(" uuid PRIMARY KEY");
        for (String c : spec.columns()) {
            sb.append(",\n    ").append(c).append(" text");
        }
        sb.append(",\n    record_hash text");
        sb.append(",\n    source_system text");
        sb.append(",\n    source_interface text");
        sb.append(",\n    source_object_type text");
        sb.append(",\n    src_natural_key text");
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
        StringJoiner set = new StringJoiner(", ");
        cols.add(spec.pkColumn());
        vals.add("?::uuid");
        for (String c : spec.columns()) {
            cols.add(c);
            vals.add("?");
            set.add(c + " = EXCLUDED." + c);
        }
        for (String meta : List.of("record_hash", "source_system", "source_interface", "source_object_type",
                "src_natural_key", "extraction_run_id")) {
            cols.add(meta);
            vals.add("?");
            set.add(meta + " = EXCLUDED." + meta);
        }
        cols.add("extracted_at"); vals.add("now()");  set.add("extracted_at = now()");
        cols.add("is_deleted");   vals.add("false");  set.add("is_deleted = false");
        cols.add("deleted_at");   vals.add("null");   set.add("deleted_at = null");
        return "INSERT INTO " + table + " AS report_row (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", vals) + ") ON CONFLICT (" + spec.pkColumn() + ") DO UPDATE SET " + set
                + " WHERE report_row.record_hash IS DISTINCT FROM EXCLUDED.record_hash OR report_row.is_deleted"
                + " RETURNING (xmax = 0)";
    }

    /** Exposed for tests. */
    public String createTableSql() {
        return createTableSql;
    }

    /** Exposed for tests. */
    public String upsertSql() {
        return upsertSql;
    }
}

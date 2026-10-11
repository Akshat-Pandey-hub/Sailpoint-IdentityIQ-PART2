package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Generic plain-JDBC persistence for a current-state {@code iiq_native} table described by an
 * {@link EntitySpec}, written to a configurable DESTINATION schema.
 *
 * <p>Column layout (matches iiq_native.sql): {@code <pk> uuid PRIMARY KEY}, the business columns,
 * {@code record_hash} (only when the entity has one), the lineage columns {@code source_system,
 * source_interface, source_object_type, extraction_run_id, extracted_at, is_deleted, deleted_at}, then any
 * {@link EntitySpec#trailing() trailing} columns iiq_native appends after them.
 *
 * <p>Write: {@code INSERT … ON CONFLICT (<pk>) DO UPDATE}; the pk comes from the spec's deterministic key
 * function, so a re-run updates in place. A row present in the source is by definition not deleted, so
 * every write sets {@code is_deleted=false, deleted_at=null} (same as Identity; there is no sweep yet).
 * All values are bound parameters; the schema identifier is validated, table/column names come from code.
 */
public final class EntityRepository {

    public enum Outcome { INSERTED, UPDATED }

    private static final List<String> LINEAGE_PARAMS =
            List.of("source_system", "source_interface", "source_object_type", "extraction_run_id");

    private final EntitySpec spec;
    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String upsertSql;

    public EntityRepository(String schema, EntitySpec spec) {
        this.spec = spec;
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + spec.table();
        this.createTableSql = buildCreateTable();
        this.upsertSql = buildUpsert();
    }

    public EntitySpec spec() {
        return spec;
    }

    public String targetTable() {
        return table;
    }

    /** Create the destination schema + table if absent (never alters an existing table or its data). */
    public void ensureSchemaAndTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql);
        }
    }

    /** Upsert one JSON row. Returns whether a new row was inserted or an existing one updated. */
    public Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        String key = spec.keyFn().apply(row);
        if (key == null) {
            throw new IllegalArgumentException("no " + spec.pkColumn() + " could be derived");
        }
        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            int i = 1;
            ps.setString(i++, key); // ?::uuid
            for (ColumnSpec c : spec.allBusiness()) {
                JsonColumnBinder.bind(ps, i++, c, row.get(c.name));
            }
            if (spec.hasRecordHash()) {
                ps.setString(i++, spec.recordHashFn().apply(row));
            }
            ps.setString(i++, spec.sourceSystem());
            ps.setString(i++, spec.sourceInterface());
            ps.setString(i++, spec.sourceObjectType());
            ps.setString(i, extractionRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1) ? Outcome.INSERTED : Outcome.UPDATED;
            }
        }
    }

    // ── SQL builders ──

    private String buildCreateTable() {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n");
        sb.append("    ").append(spec.pkColumn()).append(" uuid PRIMARY KEY");
        for (ColumnSpec c : spec.business()) {
            sb.append(",\n    ").append(c.name).append(' ').append(c.kind.ddlType);
        }
        if (spec.hasRecordHash()) {
            sb.append(",\n    record_hash text");
        }
        sb.append(",\n    source_system text");
        sb.append(",\n    source_interface text");
        sb.append(",\n    source_object_type text");
        sb.append(",\n    extraction_run_id text");
        sb.append(",\n    extracted_at timestamp with time zone NOT NULL DEFAULT now()");
        sb.append(",\n    is_deleted boolean NOT NULL DEFAULT false");
        sb.append(",\n    deleted_at timestamp with time zone");
        for (ColumnSpec c : spec.trailing()) {
            sb.append(",\n    ").append(c.name).append(' ').append(c.kind.ddlType);
        }
        sb.append("\n)");
        return sb.toString();
    }

    private String buildUpsert() {
        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        StringJoiner set = new StringJoiner(", ");

        cols.add(spec.pkColumn());
        vals.add("?::uuid");
        for (ColumnSpec c : spec.allBusiness()) {
            cols.add(c.name);
            vals.add(c.kind.placeholder);
            set.add(c.name + " = EXCLUDED." + c.name);
        }
        if (spec.hasRecordHash()) {
            cols.add("record_hash");
            vals.add("?");
            set.add("record_hash = EXCLUDED.record_hash");
        }
        for (String meta : LINEAGE_PARAMS) {
            cols.add(meta);
            vals.add("?");
            set.add(meta + " = EXCLUDED." + meta);
        }
        cols.add("extracted_at"); vals.add("now()");  set.add("extracted_at = now()");
        cols.add("is_deleted");   vals.add("false");  set.add("is_deleted = false");
        cols.add("deleted_at");   vals.add("null");   set.add("deleted_at = null");

        return "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", vals) + ") "
                + "ON CONFLICT (" + spec.pkColumn() + ") DO UPDATE SET " + set + " RETURNING (xmax = 0)";
    }

    /** Exposed for tests. */
    public String upsertSql() {
        return upsertSql;
    }

    /** Exposed for tests. */
    public String createTableSql() {
        return createTableSql;
    }
}

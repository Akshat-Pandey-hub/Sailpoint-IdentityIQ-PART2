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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /kfagent/role-hierarchy} → {@code kf_role_hierarchy}: Bundle-to-Bundle edges (INHERITANCE, REQUIREMENT,
 * PERMIT), reproducing Part 1's {@code NativeRoleHierarchyRepository} + {@code NativeRoleRelationshipMapper.addHierarchy}.
 * A dedicated {@link EntityWriter} because Part 1 persists columns the response omits; they are DERIVED here:
 * <ul>
 *   <li>The OWNING role is {@code source_role_id} / {@code role_name}; the RELATED (inherited / required / permitted)
 *       role is {@code related_role_source_id} / {@code related_role_name}. Raw IIQ Bundle ids are kept verbatim.</li>
 *   <li>{@code src_natural_key} = {@code part(source_role_id) + part(relationship_type) + part(related_role_source_id)}
 *       ({@code part(v)} = {@code v.length() + ":" + v}, or {@code "-1:"} for null), exactly as the mapper builds it.</li>
 *   <li>{@code rolehierarchyid} (PK) = {@code deterministicUuid("native-role-hierarchy|" + src_natural_key)}, so the
 *       same two roles with a different relationship type are different edges.</li>
 *   <li>{@code role_id} / {@code related_role_id uuid} = canonical UUID of each Bundle id, fallback
 *       {@code deterministicUuid("native-role|" + id)}; {@code src_object_id} = source_role_id.</li>
 * </ul>
 * {@code record_hash} is Part 1's 5-field hash, which uses its OWN key names: {@code source_role_name} (column
 * role_name) and {@code related_role_id} holding the RAW related Bundle id (column related_role_source_id), not the
 * uuid column. Lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.Bundle}.
 */
public final class RoleHierarchyRepository implements EntityWriter {

    public static final String RESOURCE = "role-hierarchy";
    public static final String TABLE = "kf_role_hierarchy";
    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Bundle";

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String upsertSql;

    public RoleHierarchyRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        this.createTableSql = "CREATE TABLE IF NOT EXISTS " + table + " (\n"
                + "    rolehierarchyid uuid PRIMARY KEY,\n"
                + "    source_role_id text NOT NULL,\n"
                + "    role_id uuid,\n"
                + "    role_name text,\n"
                + "    related_role_source_id text NOT NULL,\n"
                + "    related_role_id uuid,\n"
                + "    related_role_name text,\n"
                + "    relationship_type text NOT NULL,\n"
                + "    source_system text,\n"
                + "    source_interface text,\n"
                + "    source_object_type text,\n"
                + "    src_object_id text,\n"
                + "    src_natural_key text NOT NULL,\n"
                + "    extraction_run_id text,\n"
                + "    extracted_at timestamp with time zone NOT NULL DEFAULT now(),\n"
                + "    record_hash text,\n"
                + "    is_deleted boolean NOT NULL DEFAULT false,\n"
                + "    deleted_at timestamp with time zone\n"
                + ")";
        this.upsertSql = "INSERT INTO " + table + " AS " + UpsertSql.ALIAS + " (rolehierarchyid, source_role_id, role_id, role_name, "
                + "related_role_source_id, related_role_id, related_role_name, relationship_type, source_system, "
                + "source_interface, source_object_type, src_object_id, src_natural_key, extraction_run_id, record_hash) "
                + "VALUES (?::uuid, ?, ?::uuid, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + UpsertSql.tail("rolehierarchyid", List.of("source_role_id", "role_id", "role_name",
                        "related_role_source_id", "related_role_id", "related_role_name", "relationship_type",
                        "src_object_id", "src_natural_key", "record_hash"),
                        List.of("source_system", "source_interface", "source_object_type", "extraction_run_id"), true);
    }

    @Override
    public String entity() {
        return "RoleHierarchy";
    }

    @Override
    public String resource() {
        return RESOURCE;
    }

    @Override
    public String targetTable() {
        return table;
    }

    @Override
    public void ensureSchemaAndTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql);
        }
    }

    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        String sourceRoleId = text(row, "source_role_id");
        String relatedRoleId = text(row, "related_role_source_id");
        String naturalKey = naturalKey(row);
        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            int i = 1;
            ps.setString(i++, id(row));
            ps.setString(i++, sourceRoleId);
            ps.setString(i++, roleUuid(sourceRoleId));
            ps.setString(i++, text(row, "role_name"));
            ps.setString(i++, relatedRoleId);
            ps.setString(i++, roleUuid(relatedRoleId));
            ps.setString(i++, text(row, "related_role_name"));
            ps.setString(i++, text(row, "relationship_type"));
            ps.setString(i++, SOURCE_SYSTEM);
            ps.setString(i++, SOURCE_INTERFACE);
            ps.setString(i++, SOURCE_OBJECT_TYPE);
            ps.setString(i++, sourceRoleId);
            ps.setString(i++, naturalKey);
            ps.setString(i++, extractionRunId);
            ps.setString(i, recordHash(row));
            try (ResultSet rs = ps.executeQuery()) {
                return UpsertSql.outcome(rs);
            }
        }
    }

    /** Part 1 mapper: {@code part(source.getId()) + part(type) + part(target.getId())}. */
    public static String naturalKey(JsonNode row) {
        return part(text(row, "source_role_id")) + part(text(row, "relationship_type"))
                + part(text(row, "related_role_source_id"));
    }

    /** Part 1 {@code NativeRoleHierarchyRepository.id}. */
    /** The PK {@link #upsert} writes. */
    @Override
    public String key(JsonNode row) {
        return id(row);
    }

    @Override
    public String keyColumn() {
        return "rolehierarchyid";
    }

    public static String id(JsonNode row) {
        return CanonicalIds.deterministicUuid("native-role-hierarchy|" + naturalKey(row));
    }

    /** Part 1 {@code NativeRoleHierarchyRepository.uuid}. */
    public static String roleUuid(String raw) {
        String v = CanonicalIds.canonicalUuid(raw);
        return v == null ? CanonicalIds.deterministicUuid("native-role|" + raw) : v;
    }

    /** Part 1 {@code NativeRoleHierarchyRepository.hash} — note its own key names (see class doc). */
    public static String recordHash(JsonNode row) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source_role_id", text(row, "source_role_id"));
        b.put("source_role_name", text(row, "role_name"));
        b.put("related_role_id", text(row, "related_role_source_id"));
        b.put("related_role_name", text(row, "related_role_name"));
        b.put("relationship_type", text(row, "relationship_type"));
        return RecordHash.of(b);
    }

    @Override
    public String ref(JsonNode row) {
        return text(row, "role_name") + " -" + text(row, "relationship_type") + "-> " + text(row, "related_role_name")
                + " (" + text(row, "source_role_id") + " -> " + text(row, "related_role_source_id") + ")";
    }

    /** Exposed for tests. */
    public String createTableSql() {
        return createTableSql;
    }

    /** Exposed for tests. */
    public String upsertSql() {
        return upsertSql;
    }

    private static String part(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static String text(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }
}

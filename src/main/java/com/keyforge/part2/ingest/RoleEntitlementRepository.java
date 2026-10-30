package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.config.SchemaName;
import com.keyforge.part2.hash.RecordHash;
import com.keyforge.part2.id.CanonicalIds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code GET /kfagent/role-entitlements} → {@code kf_role_entitlement}: what a role grants (Bundle profile
 * constraints and permissions), reproducing Part 1's {@code NativeRoleEntitlementRepository} +
 * {@code NativeRoleRelationshipMapper.naturalKey}. A dedicated {@link EntityWriter} because Part 1 persists columns
 * the KF Agent response deliberately omits, and they must be DERIVED exactly as Part 1 derives them:
 * <ul>
 *   <li>{@code src_natural_key} — the mapper's length-prefixed natural key over source_role_id, profile_ordinal,
 *       entitlement_type, constraint_path, application_id, attribute_name, filter_operation, filter_value,
 *       permission_target, permission_rights, permission_rights_list, permission_annotation;</li>
 *   <li>{@code roleentitlementid} (PK) = {@code deterministicUuid("native-role-entitlement|" + src_natural_key)};</li>
 *   <li>{@code role_id uuid} = canonical(source_role_id), fallback {@code deterministicUuid("native-role|"+id)};</li>
 *   <li>{@code src_object_id} = source_role_id;</li>
 *   <li>{@code entitlement_id uuid} — linked ONLY for a {@code PROFILE_CONSTRAINT} with an {@code EQ} filter whose
 *       (application_name, attribute_name, attribute_value) matches exactly ONE row of {@code <schema>.kf_entitlement}
 *       (Part 1: "do not guess"); otherwise NULL. Requires kf_entitlement in the same destination schema, so run
 *       {@code import-entitlement} first.</li>
 * </ul>
 * {@code record_hash} is Part 1's 17-field hash, which includes the resolved entitlement_id and renders
 * filter_value / permission_rights_list with Java's {@code toString} of key-sorted maps/lists. Every response field
 * is stored exactly as received (a null filter_value / permission_rights_list / profile_ordinal stays NULL).
 * Lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.Bundle}.
 */
public final class RoleEntitlementRepository implements EntityWriter {

    public static final String RESOURCE = "role-entitlements";
    public static final String TABLE = "kf_role_entitlement";
    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Bundle";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String upsertSql;
    private final String lookupSql;

    public RoleEntitlementRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        this.createTableSql = "CREATE TABLE IF NOT EXISTS " + table + " (\n"
                + "    roleentitlementid uuid PRIMARY KEY,\n"
                + "    source_role_id text NOT NULL,\n"
                + "    role_id uuid,\n"
                + "    role_name text,\n"
                + "    entitlement_id uuid,\n"
                + "    entitlement_type text NOT NULL,\n"
                + "    application_id text,\n"
                + "    application_name text,\n"
                + "    profile_ordinal integer,\n"
                + "    constraint_path text,\n"
                + "    filter_expression text,\n"
                + "    filter_value jsonb,\n"
                + "    filter_operation text,\n"
                + "    attribute_name text,\n"
                + "    attribute_value text,\n"
                + "    permission_target text,\n"
                + "    permission_rights text,\n"
                + "    permission_rights_list jsonb,\n"
                + "    permission_annotation text,\n"
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
        this.upsertSql = "INSERT INTO " + table + " AS " + UpsertSql.ALIAS + " (roleentitlementid, source_role_id, role_id, role_name, "
                + "entitlement_id, entitlement_type, application_id, application_name, profile_ordinal, constraint_path, "
                + "filter_expression, filter_value, filter_operation, attribute_name, attribute_value, permission_target, "
                + "permission_rights, permission_rights_list, permission_annotation, source_system, source_interface, "
                + "source_object_type, src_object_id, src_natural_key, extraction_run_id, record_hash) "
                + "VALUES (?::uuid, ?, ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, "
                + "?, ?, ?, ?) "
                + UpsertSql.tail("roleentitlementid", List.of("source_role_id", "role_id", "role_name",
                        "entitlement_id", "entitlement_type", "application_id", "application_name", "profile_ordinal",
                        "constraint_path", "filter_expression", "filter_value", "filter_operation", "attribute_name",
                        "attribute_value", "permission_target", "permission_rights", "permission_rights_list",
                        "permission_annotation", "src_object_id", "src_natural_key", "record_hash"),
                        List.of("source_system", "source_interface", "source_object_type", "extraction_run_id"), true);
        this.lookupSql = "SELECT entitlementid::text FROM " + this.schema + ".kf_entitlement "
                + "WHERE application_name = ? AND attribute = ? AND value = ? ORDER BY entitlementid LIMIT 2";
    }

    @Override
    public String entity() {
        return "RoleEntitlement";
    }

    @Override
    public String resource() {
        return RESOURCE;
    }

    @Override
    public String targetTable() {
        return table;
    }

    /** Creates the table; requires {@code <schema>.kf_entitlement} (entitlement_id resolution reads it). */
    @Override
    public void ensureSchemaAndTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql);
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT to_regclass(?)::text")) {
            ps.setString(1, schema + ".kf_entitlement");
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString(1) == null) {
                    throw new SQLException(schema + ".kf_entitlement does not exist. Run import-entitlement first: "
                            + "role-entitlement rows link entitlement_id to it, exactly as Part 1 does.");
                }
            }
        }
    }

    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        Edge e = Edge.of(row);
        String entitlementId = resolveEntitlement(conn, e);
        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            int i = 1;
            ps.setString(i++, e.id());
            ps.setString(i++, e.sourceRoleId);
            ps.setString(i++, e.roleId());
            ps.setString(i++, e.roleName);
            if (entitlementId == null) {
                ps.setNull(i++, Types.OTHER);
            } else {
                ps.setString(i++, entitlementId);
            }
            ps.setString(i++, e.entitlementType);
            ps.setString(i++, e.applicationId);
            ps.setString(i++, e.applicationName);
            // the response values are stored as received; Edge's normalised copies only feed the key and the hash
            JsonColumnBinder.bind(ps, i++, ColumnSpec.integer("profile_ordinal"), row.get("profile_ordinal"));
            ps.setString(i++, e.constraintPath);
            ps.setString(i++, e.filterExpression);
            ps.setString(i++, rawJson(row, "filter_value"));
            ps.setString(i++, e.filterOperation);
            ps.setString(i++, e.attributeName);
            ps.setString(i++, e.attributeValue);
            ps.setString(i++, e.permissionTarget);
            ps.setString(i++, e.permissionRights);
            ps.setString(i++, rawJson(row, "permission_rights_list"));
            ps.setString(i++, e.permissionAnnotation);
            ps.setString(i++, SOURCE_SYSTEM);
            ps.setString(i++, SOURCE_INTERFACE);
            ps.setString(i++, SOURCE_OBJECT_TYPE);
            ps.setString(i++, e.sourceRoleId);
            ps.setString(i++, e.naturalKey());
            ps.setString(i++, extractionRunId);
            ps.setString(i, e.recordHash(entitlementId));
            try (ResultSet rs = ps.executeQuery()) {
                return UpsertSql.outcome(rs);
            }
        }
    }

    /** Part 1 "do not guess": only an EQ constraint that matches exactly one kf_entitlement row is linked. */
    String resolveEntitlement(Connection conn, Edge e) throws SQLException {
        if (!"PROFILE_CONSTRAINT".equals(e.entitlementType) || !"EQ".equalsIgnoreCase(e.filterOperation)
                || e.applicationName == null || e.attributeName == null || e.attributeValue == null) {
            return null;
        }
        try (PreparedStatement ps = conn.prepareStatement(lookupSql)) {
            ps.setString(1, e.applicationName);
            ps.setString(2, e.attributeName);
            ps.setString(3, e.attributeValue);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String id = rs.getString(1);
                return rs.next() ? null : id;
            }
        }
    }

    /** The PK {@link #upsert} writes ({@code roleentitlementid}). */
    @Override
    public String key(JsonNode row) {
        return Edge.of(row).id();
    }

    @Override
    public String keyColumn() {
        return "roleentitlementid";
    }

    @Override
    public String ref(JsonNode row) {
        return "role " + text(row, "source_role_id") + " (" + text(row, "role_name") + ") "
                + text(row, "entitlement_type") + " path " + text(row, "constraint_path");
    }

    /** Exposed for tests. */
    public String createTableSql() {
        return createTableSql;
    }

    /** Exposed for tests. */
    public String upsertSql() {
        return upsertSql;
    }

    /** A structured response value as its compact JSON text, exactly as received; absent / JSON null → SQL NULL. */
    private static String rawJson(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return (n == null || n.isNull()) ? null : n.toString();
    }

    private static String text(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    /** One response row read with Part 1's parser semantics, plus Part 1's derivations. */
    static final class Edge {
        String sourceRoleId, roleName, entitlementType, applicationId, applicationName, constraintPath,
                filterExpression, filterOperation, attributeName, attributeValue, permissionTarget, permissionRights,
                permissionAnnotation;
        int profileOrdinal;
        Object filterValue;
        List<String> permissionRightsList;

        static Edge of(JsonNode row) {
            Edge e = new Edge();
            e.sourceRoleId = text(row, "source_role_id");
            e.roleName = text(row, "role_name");
            e.entitlementType = text(row, "entitlement_type");
            e.applicationId = text(row, "application_id");
            e.applicationName = text(row, "application_name");
            e.profileOrdinal = row.path("profile_ordinal").asInt();      // Part 1: asInt(), missing -> 0
            e.constraintPath = text(row, "constraint_path");
            e.filterExpression = text(row, "filter_expression");
            JsonNode fv = row.get("filter_value");
            e.filterValue = fv == null || fv.isNull() ? null : JSON.convertValue(fv, Object.class);
            e.filterOperation = text(row, "filter_operation");
            e.attributeName = text(row, "attribute_name");
            e.attributeValue = text(row, "attribute_value");
            e.permissionTarget = text(row, "permission_target");
            e.permissionRights = text(row, "permission_rights");
            e.permissionAnnotation = text(row, "permission_annotation");
            e.permissionRightsList = new ArrayList<>();                    // Part 1: never null; null items skipped
            JsonNode rights = row.get("permission_rights_list");
            if (rights != null && rights.isArray()) {
                for (JsonNode v : rights) {
                    if (!v.isNull()) {
                        e.permissionRightsList.add(v.asText());
                    }
                }
            }
            return e;
        }

        /** Part 1 {@code NativeRoleRelationshipMapper.naturalKey}. */
        String naturalKey() {
            return part(sourceRoleId) + part(Integer.toString(profileOrdinal)) + part(entitlementType)
                    + part(constraintPath) + part(applicationId) + part(attributeName)
                    + part(filterOperation) + part(keyCanonical(filterValue))
                    + part(permissionTarget) + part(permissionRights)
                    + part(keyCanonical(permissionRightsList)) + part(permissionAnnotation);
        }

        /** Part 1 {@code NativeRoleEntitlementRepository.id}. */
        String id() {
            return CanonicalIds.deterministicUuid("native-role-entitlement|" + naturalKey());
        }

        /** Part 1 {@code NativeRoleEntitlementRepository.uuid}: canonical role id, else name-based. */
        String roleId() {
            String value = CanonicalIds.canonicalUuid(sourceRoleId);
            return value == null ? CanonicalIds.deterministicUuid("native-role|" + sourceRoleId) : value;
        }

        /** Part 1 {@code NativeRoleEntitlementRepository.hash}. */
        String recordHash(String entitlementId) {
            Map<String, Object> b = new TreeMap<>();
            b.put("source_role_id", sourceRoleId);
            b.put("role_name", roleName);
            b.put("entitlement_id", entitlementId);
            b.put("entitlement_type", entitlementType);
            b.put("application_id", applicationId);
            b.put("application_name", applicationName);
            b.put("profile_ordinal", profileOrdinal);
            b.put("constraint_path", constraintPath);
            b.put("filter_expression", filterExpression);
            b.put("filter_value", hashCanonical(filterValue));
            b.put("filter_operation", filterOperation);
            b.put("attribute_name", attributeName);
            b.put("attribute_value", attributeValue);
            b.put("permission_target", permissionTarget);
            b.put("permission_rights", permissionRights);
            b.put("permission_rights_list", hashCanonical(permissionRightsList));
            b.put("permission_annotation", permissionAnnotation);
            return RecordHash.of(b);
        }

        private static String part(String value) {
            return value == null ? "-1:" : value.length() + ":" + value;
        }

        /** Part 1 mapper {@code canonical(Object)}: length-prefixed, key-sorted, class-qualified scalars. */
        private static String keyCanonical(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof Map<?, ?> map) {
                TreeMap<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> en : map.entrySet()) {
                    sorted.put(String.valueOf(en.getKey()), en.getValue());
                }
                StringBuilder out = new StringBuilder("{");
                for (Map.Entry<String, Object> en : sorted.entrySet()) {
                    out.append(part(en.getKey())).append(part(keyCanonical(en.getValue())));
                }
                return out.append('}').toString();
            }
            if (value instanceof List<?> list) {
                StringBuilder out = new StringBuilder("[");
                for (Object item : list) {
                    out.append(part(keyCanonical(item)));
                }
                return out.append(']').toString();
            }
            return value.getClass().getName() + ":" + value;
        }

        /** Part 1 repository {@code canonical(Object)}: key-sorted maps / lists, hashed via their toString. */
        private static Object hashCanonical(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> en : map.entrySet()) {
                    sorted.put(String.valueOf(en.getKey()), hashCanonical(en.getValue()));
                }
                return sorted;
            }
            if (value instanceof List<?> list) {
                List<Object> out = new ArrayList<>();
                for (Object item : list) {
                    out.add(hashCanonical(item));
                }
                return out;
            }
            return value;
        }
    }
}

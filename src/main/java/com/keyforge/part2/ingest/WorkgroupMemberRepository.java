package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.SchemaName;
import com.keyforge.part2.id.CanonicalIds;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Objects;

/**
 * {@code GET /kfagent/workgroupmember} → {@code kf_workgroup_member}: one row per workgroup ↔ member identity edge
 * ({@code Identity.getWorkgroups()}), reproducing Part 1's {@code NativeWorkgroupMembershipRepository}. A dedicated
 * {@link EntityWriter} because its rules differ from every other table:
 * <ul>
 *   <li>Every response field is stored exactly as received, under its own name: {@code workgroup_id},
 *       {@code workgroup_name}, {@code identity_id} (the RAW IIQ ids), {@code member_name}, {@code first_name},
 *       {@code last_name}, {@code member_is_workgroup}.</li>
 *   <li>PK {@code id} = {@code deterministicUuid("native-workgroup-member|" + part(workgroup_id) + "|" +
 *       part(identity_id))}, where a GUID-shaped id is used in its canonical dashed form (so existing keys never change)
 *       and any other id as received: the (workgroup, member) pair, so a member of two workgroups is two rows.</li>
 *   <li>Lineage: {@code src_system} IdentityIQ, {@code src_interface} native_iiq_java_api, {@code src_object_type}
 *       sailpoint.object.Identity.workgroups, {@code src_object_id} = the RAW member identity id,
 *       {@code src_natural_key} = RAW {@code workgroup_id|identity_id}; is_deleted=false / deleted_at=null on write.</li>
 *   <li>{@code record_hash} is NOT the shared SHA-256 of sorted key=value lines: it is SHA-256 (lowercase hex) of
 *       {@code workgroupId|identityId|workgroupName|identityName|firstName|lastName|memberIsWorkgroup} over the RAW
 *       values, nulls as "".</li>
 *   <li>{@code member_is_workgroup} is taken only from a real JSON boolean (Part 1's parser), otherwise NULL.</li>
 * </ul>
 */
public final class WorkgroupMemberRepository implements EntityWriter {

    public static final String RESOURCE = "workgroupmember";
    public static final String TABLE = "kf_workgroup_member";
    public static final String SRC_SYSTEM = "IdentityIQ";
    public static final String SRC_INTERFACE = "native_iiq_java_api";
    public static final String SRC_OBJECT_TYPE = "sailpoint.object.Identity.workgroups";

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String upsertSql;

    public WorkgroupMemberRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        this.createTableSql = "CREATE TABLE IF NOT EXISTS " + table + " (\n"
                + "    id uuid PRIMARY KEY,\n"
                + "    workgroup_id text,\n"
                + "    workgroup_name text,\n"
                + "    identity_id text,\n"
                + "    member_name text,\n"
                + "    first_name text,\n"
                + "    last_name text,\n"
                + "    member_is_workgroup boolean,\n"
                + "    src_system text,\n"
                + "    src_interface text,\n"
                + "    src_object_type text,\n"
                + "    src_object_id text,\n"
                + "    src_natural_key text,\n"
                + "    extraction_run_id text,\n"
                + "    record_hash text,\n"
                + "    is_deleted boolean NOT NULL DEFAULT false,\n"
                + "    deleted_at timestamp with time zone,\n"
                + "    extracted_at timestamp with time zone NOT NULL DEFAULT now()\n"
                + ")";
        this.upsertSql = "INSERT INTO " + table + " AS " + UpsertSql.ALIAS + " (id, workgroup_id, workgroup_name, identity_id, member_name, first_name, "
                + "last_name, member_is_workgroup, src_system, src_interface, src_object_type, src_object_id, "
                + "src_natural_key, extraction_run_id, record_hash, is_deleted, deleted_at) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, null) "
                + UpsertSql.tail("id", List.of("workgroup_id", "workgroup_name", "identity_id", "member_name", "first_name",
                        "last_name", "member_is_workgroup", "src_object_id", "src_natural_key", "record_hash"),
                        List.of("src_system", "src_interface", "src_object_type", "extraction_run_id"), true);
    }

    @Override
    public String entity() {
        return "WorkgroupMember";
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
            // a table from the earlier layout: add the response's workgroup_name, and keep the ids as received
            st.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS workgroup_name text");
        }
        for (String column : List.of("workgroup_id", "identity_id")) {
            if ("uuid".equals(columnType(conn, column))) {
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " TYPE text USING replace("
                            + column + "::text, '-', '')");
                }
            }
        }
    }

    private String columnType(Connection conn, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, TABLE);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        String workgroupId = text(row, "workgroup_id");
        String identityId = text(row, "identity_id");
        Boolean memberIsWorkgroup = memberIsWorkgroup(row);
        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            int i = 1;
            ps.setString(i++, id(row));
            ps.setString(i++, workgroupId);
            ps.setString(i++, text(row, "workgroup_name"));
            ps.setString(i++, identityId);
            ps.setString(i++, text(row, "member_name"));
            ps.setString(i++, text(row, "first_name"));
            ps.setString(i++, text(row, "last_name"));
            if (memberIsWorkgroup == null) {
                ps.setNull(i++, Types.BOOLEAN);
            } else {
                ps.setBoolean(i++, memberIsWorkgroup);
            }
            ps.setString(i++, SRC_SYSTEM);
            ps.setString(i++, SRC_INTERFACE);
            ps.setString(i++, SRC_OBJECT_TYPE);
            ps.setString(i++, identityId);                         // src_object_id = raw member identity id
            ps.setString(i++, workgroupId + "|" + identityId);     // src_natural_key = raw workgroup|identity
            ps.setString(i++, extractionRunId);
            ps.setString(i, recordHash(row));
            try (ResultSet rs = ps.executeQuery()) {
                return UpsertSql.outcome(rs);
            }
        }
    }

    /** An id's part of the row key: its canonical dashed UUID when GUID-shaped (as before), otherwise as received. */
    static String keyPart(String rawId) {
        String x = CanonicalIds.canonicalUuid(rawId);
        return x != null ? x : String.valueOf(rawId);
    }

    /** The PK {@link #upsert} writes. */
    @Override
    public String key(JsonNode row) {
        return id(row);
    }

    @Override
    public String keyColumn() {
        return "id";
    }

    /** The row uuid of the (workgroup, member) pair. */
    public static String id(JsonNode row) {
        return CanonicalIds.deterministicUuid("native-workgroup-member|" + keyPart(text(row, "workgroup_id")) + "|"
                + keyPart(text(row, "identity_id")));
    }

    /** Part 1 {@code NativeWorkgroupMembershipRepository.hash}: SHA-256 hex of the pipe-joined raw values. */
    public static String recordHash(JsonNode row) {
        String v = String.join("|",
                Objects.toString(text(row, "workgroup_id"), ""),
                Objects.toString(text(row, "identity_id"), ""),
                Objects.toString(text(row, "workgroup_name"), ""),
                Objects.toString(text(row, "member_name"), ""),
                Objects.toString(text(row, "first_name"), ""),
                Objects.toString(text(row, "last_name"), ""),
                Objects.toString(memberIsWorkgroup(row), ""));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                b.append(String.format("%02x", x & 255));
            }
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Part 1 parser: only a real JSON boolean counts. */
    static Boolean memberIsWorkgroup(JsonNode row) {
        JsonNode n = row.path("member_is_workgroup");
        return n.isBoolean() ? n.asBoolean() : null;
    }

    @Override
    public String ref(JsonNode row) {
        return "member " + text(row, "member_name") + " (" + text(row, "identity_id") + ") of workgroup "
                + text(row, "workgroup_name") + " (" + text(row, "workgroup_id") + ")";
    }

    /** Exposed for tests. */
    public String createTableSql() {
        return createTableSql;
    }

    /** Exposed for tests. */
    public String upsertSql() {
        return upsertSql;
    }

    private static String text(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return (n == null || n.isNull()) ? null : (n.isValueNode() ? n.asText() : n.toString());
    }
}

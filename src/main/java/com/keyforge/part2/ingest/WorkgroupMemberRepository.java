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
import java.util.Objects;

/**
 * {@code GET /kfagent/workgroupmember} → {@code kf_workgroup_member}: one row per workgroup ↔ member identity edge
 * ({@code Identity.getWorkgroups()}), reproducing Part 1's {@code NativeWorkgroupMembershipRepository}. A dedicated
 * {@link EntityWriter} because its rules differ from every other table:
 * <ul>
 *   <li>The response carries the RAW IIQ ids ({@code workgroup_id}, {@code identity_id}); the table's
 *       {@code workgroup_id} / {@code identity_id} columns are their canonical UUIDs. Both MUST be GUID-shaped — Part 1
 *       refuses the edge otherwise (row fails), there is no fallback.</li>
 *   <li>PK {@code id} = {@code deterministicUuid("native-workgroup-member|" + canonical(workgroup_id) + "|" +
 *       canonical(identity_id))}: the (workgroup, member) pair, so a member of two workgroups is two rows.</li>
 *   <li>Lineage: {@code src_system} IdentityIQ, {@code src_interface} native_iiq_java_api, {@code src_object_type}
 *       sailpoint.object.Identity.workgroups, {@code src_object_id} = the RAW member identity id,
 *       {@code src_natural_key} = RAW {@code workgroup_id|identity_id}; is_deleted=false / deleted_at=null on write.</li>
 *   <li>{@code record_hash} is NOT the shared SHA-256 of sorted key=value lines: it is SHA-256 (lowercase hex) of
 *       {@code workgroupId|identityId|workgroupName|identityName|firstName|lastName|memberIsWorkgroup} over the RAW
 *       values, nulls as "". It includes {@code workgroup_name}, which has no column of its own.</li>
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
                + "    workgroup_id uuid,\n"
                + "    identity_id uuid,\n"
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
        this.upsertSql = "INSERT INTO " + table + " (id, workgroup_id, identity_id, member_name, first_name, last_name, "
                + "member_is_workgroup, src_system, src_interface, src_object_type, src_object_id, src_natural_key, "
                + "extraction_run_id, record_hash, is_deleted, deleted_at) "
                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, null) "
                + "ON CONFLICT (id) DO UPDATE SET workgroup_id = EXCLUDED.workgroup_id, identity_id = EXCLUDED.identity_id, "
                + "member_name = EXCLUDED.member_name, first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name, "
                + "member_is_workgroup = EXCLUDED.member_is_workgroup, src_system = EXCLUDED.src_system, "
                + "src_interface = EXCLUDED.src_interface, src_object_type = EXCLUDED.src_object_type, "
                + "src_object_id = EXCLUDED.src_object_id, src_natural_key = EXCLUDED.src_natural_key, "
                + "extraction_run_id = EXCLUDED.extraction_run_id, record_hash = EXCLUDED.record_hash, "
                + "is_deleted = false, deleted_at = null, extracted_at = now() RETURNING (xmax = 0)";
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
            ps.setString(i++, uuid(workgroupId));
            ps.setString(i++, uuid(identityId));
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
                return rs.next() && rs.getBoolean(1) ? EntityRepository.Outcome.INSERTED : EntityRepository.Outcome.UPDATED;
            }
        }
    }

    /** Part 1 {@code NativeWorkgroupMembershipRepository.uuid}: canonical UUID, or the edge is refused. */
    public static String uuid(String rawId) {
        String x = CanonicalIds.canonicalUuid(rawId);
        if (x == null) {
            throw new IllegalArgumentException("Native identity/workgroup ID is not UUID-shaped: " + rawId);
        }
        return x;
    }

    /** Part 1 {@code NativeWorkgroupMembershipRepository.edgeId}. */
    public static String id(JsonNode row) {
        return CanonicalIds.deterministicUuid("native-workgroup-member|" + uuid(text(row, "workgroup_id")) + "|"
                + uuid(text(row, "identity_id")));
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
        return (n == null || n.isNull()) ? null : n.asText();
    }
}

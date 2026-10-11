package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.hash.RecordHash;
import com.keyforge.part2.id.CanonicalIds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.bool;
import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.json;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/identity-roles} → {@code kf_identity_role}: one row per identity↔role edge (assigned or
 * detected), taken from Part 1's iiq_native.sql + {@code NativeIdentityRoleRepository} /
 * {@code NativeIdentityRoleRow}:
 * <ul>
 *   <li>The response has NO {@code source_id}: an edge is not a standalone IIQ object. PK
 *       {@code identityroleid uuid} = {@link CanonicalIds#identityRoleId} over
 *       {@code identity_id|role_id|relationship_type|assignment_id}. It is NOT the identity id or the role id.</li>
 *   <li>19 business columns = the 19 response fields, same names, table order; {@code targets} is the only
 *       {@code jsonb}; {@code detection_assignment_ids} is text.</li>
 *   <li>{@code record_hash} = Part 1's 18-field subset: every field EXCEPT {@code identity_name}.</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.Identity.roles}</li>
 * </ul>
 * {@code identity_id} / {@code role_id} are raw IIQ ids (text); kf_identity_role has no foreign keys.
 */
public final class IdentityRoleTable {

    private IdentityRoleTable() {
    }

    public static final String RESOURCE = "identity-roles";
    public static final String TABLE = "kf_identity_role";
    public static final String PK = "identityroleid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Identity.roles";

    /** Business columns in iiq_native.kf_identity_role order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("identity_id"),
            ColumnSpec.text("identity_name"),
            ColumnSpec.text("role_id"),
            ColumnSpec.text("role_name"),
            ColumnSpec.text("relationship_type"),
            ColumnSpec.text("assignment_id"),
            ColumnSpec.text("assigner"),
            ColumnSpec.timestamptz("assigned_date"),
            ColumnSpec.timestamptz("start_date"),
            ColumnSpec.timestamptz("end_date"),
            ColumnSpec.text("source"),
            ColumnSpec.bool("negative"),
            ColumnSpec.bool("manual"),
            ColumnSpec.text("detection_assignment_ids"),
            ColumnSpec.text("comments"),
            ColumnSpec.bool("future_assignment"),
            ColumnSpec.bool("promoted_soft_permit"),
            ColumnSpec.timestamptz("detection_date"),
            ColumnSpec.jsonb("targets"));

    public static final EntitySpec SPEC = new EntitySpec(
            "IdentityRole", RESOURCE, TABLE, PK, BUSINESS,
            IdentityRoleTable::identityRoleId,
            IdentityRoleTable::recordHash,
            IdentityRoleTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String identityRoleId(JsonNode row) {
        return CanonicalIds.identityRoleId(text(row, "identity_id"), text(row, "role_id"),
                text(row, "relationship_type"), text(row, "assignment_id"));
    }

    /** Part 1 {@code NativeIdentityRoleRepository.recordHash}: 18 fields — all but {@code identity_name}. */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("identity_id", text(r, "identity_id"));
        b.put("role_id", text(r, "role_id"));
        b.put("role_name", text(r, "role_name"));
        b.put("relationship_type", text(r, "relationship_type"));
        b.put("assignment_id", text(r, "assignment_id"));
        b.put("assigner", text(r, "assigner"));
        b.put("assigned_date", instant(r, "assigned_date"));
        b.put("start_date", instant(r, "start_date"));
        b.put("end_date", instant(r, "end_date"));
        b.put("source", text(r, "source"));
        b.put("negative", bool(r, "negative"));
        b.put("manual", bool(r, "manual"));
        b.put("detection_assignment_ids", text(r, "detection_assignment_ids"));
        b.put("comments", text(r, "comments"));
        b.put("future_assignment", bool(r, "future_assignment"));
        b.put("promoted_soft_permit", bool(r, "promoted_soft_permit"));
        b.put("detection_date", instant(r, "detection_date"));
        b.put("targets", json(r, "targets"));
        return RecordHash.of(b);
    }

    private static String ref(JsonNode row) {
        return text(row, "identity_name") + "→" + text(row, "role_name") + " (" + text(row, "relationship_type")
                + ", identity_id=" + text(row, "identity_id") + ", role_id=" + text(row, "role_id") + ")";
    }
}

package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/roles} → {@code kf_role} (IIQ {@code Bundle}), taken from Part 1's iiq_native.sql +
 * {@code NativeRoleRepository} / {@code NativeRoleRow}:
 * <ul>
 *   <li>PK {@code roleid uuid} = {@link CanonicalIds#roleId} (not in the response)</li>
 *   <li>33 response fields, same names: 27 {@link #BUSINESS} columns before {@code record_hash}, plus 6
 *       {@link #TRAILING} columns that iiq_native.kf_role places after {@code deleted_at} (Part 1 added them
 *       later with {@code ALTER TABLE}). {@code risk_score_weight} is integer.</li>
 *   <li>Only {@code descriptions}, {@code attributes} and {@code selector} are {@code jsonb}.
 *       {@code role_type_definition}, {@code applications}, {@code monitored_applications}, {@code scorecard}
 *       and {@code selector_summary} are {@code text} in Part 1 (read with {@code text()}), so they stay text
 *       here, even when their content looks like JSON.</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 33 fields (Part 1's {@code recordHash})</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.Bundle}</li>
 * </ul>
 */
public final class RoleTable {

    private RoleTable() {
    }

    public static final String RESOURCE = "roles";
    public static final String TABLE = "kf_role";
    public static final String PK = "roleid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Bundle";

    /** Columns between the PK and {@code record_hash}, in iiq_native.kf_role order. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("display_name"),
            ColumnSpec.text("displayable_name"),
            ColumnSpec.text("full_name"),
            ColumnSpec.text("description"),
            ColumnSpec.text("type"),
            ColumnSpec.text("assignment_id"),
            ColumnSpec.bool("activity_enabled"),
            ColumnSpec.bool("allow_duplicate_accounts"),
            ColumnSpec.bool("allow_multiple_assignments"),
            ColumnSpec.bool("auto_promotion"),
            ColumnSpec.bool("differencable"),
            ColumnSpec.bool("iiq_elevated_access"),
            ColumnSpec.bool("merge_templates"),
            ColumnSpec.bool("or_profiles"),
            ColumnSpec.bool("pending_delete"),
            ColumnSpec.bool("has_selector"),
            ColumnSpec.integer("risk_score_weight"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.timestamptz("activation_date"),
            ColumnSpec.timestamptz("deactivation_date"),
            ColumnSpec.jsonb("descriptions"),
            ColumnSpec.jsonb("attributes"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    /** Columns iiq_native.kf_role places after {@code deleted_at}, in table order. */
    public static final List<ColumnSpec> TRAILING = List.of(
            ColumnSpec.text("role_type_definition"),
            ColumnSpec.text("applications"),
            ColumnSpec.text("monitored_applications"),
            ColumnSpec.text("scorecard"),
            ColumnSpec.text("selector_summary"),
            ColumnSpec.jsonb("selector"));

    public static final EntitySpec SPEC = new EntitySpec(
            "Role", RESOURCE, TABLE, PK, BUSINESS,
            RoleTable::roleId,
            RoleTable::recordHash,
            RoleTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE,
            TRAILING);

    public static String roleId(JsonNode row) {
        return CanonicalIds.byIdNaturalKeyOrContent(row, "native-role", () -> CanonicalIds.roleId(text(row, "source_id"), text(row, "name")), "name");
    }

    /** Part 1 {@code NativeRoleRepository.recordHash}: all 33 response fields, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, SPEC.allBusiness());
    }

    private static String ref(JsonNode row) {
        String id = text(row, "source_id");
        if (id != null) {
            return id;
        }
        String name = text(row, "name");
        return name != null ? name : "<no source_id>";
    }
}

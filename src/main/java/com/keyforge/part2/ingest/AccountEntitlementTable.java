package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.hash.RecordHash;
import com.keyforge.part2.id.CanonicalIds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.json;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/account-entitlements} → {@code kf_account_entitlement}: one row per entitlement value
 * literally present on an account (Link), taken from Part 1's iiq_native.sql +
 * {@code NativeAccountEntitlementRepository} / {@code NativeAccountEntitlementMapper}:
 * <ul>
 *   <li>Three edge kinds, named by {@code type}: {@code ATTRIBUTE} (Link entitlement attribute value),
 *       {@code PERMISSION} (Link permission), {@code TARGET_PERMISSION} (Link target permission).</li>
 *   <li>No {@code source_id} (an edge is not a standalone IIQ object). PK {@code accountentitlementid uuid} =
 *       {@link CanonicalIds#accountEntitlementId}: name-based, with a type-dependent formula.</li>
 *   <li>{@code source_object_type} is per row: {@code sailpoint.object.Link.entitlementAttributes} /
 *       {@code .permissions} / {@code .targetPermissions}, as Part 1's mapper stamps them.</li>
 *   <li>16 response fields, all text except {@code permission_rights_list} and {@code permission_attributes}
 *       ({@code jsonb}); no timestamps. 9 columns precede {@code record_hash}; {@code type} and the 6
 *       {@code permission_*} columns are {@link #TRAILING} (iiq_native places them after {@code deleted_at}).</li>
 *   <li>{@code record_hash} = Part 1's 14-field subset: all but {@code identity_name} and {@code application_id}.</li>
 * </ul>
 */
public final class AccountEntitlementTable {

    private AccountEntitlementTable() {
    }

    public static final String RESOURCE = "account-entitlements";
    public static final String TABLE = "kf_account_entitlement";
    public static final String PK = "accountentitlementid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";

    /** Part 1 object types per edge kind ({@code NativeAccountEntitlementRow} default + mapper). */
    public static final String OBJECT_TYPE_ATTRIBUTE = "sailpoint.object.Link.entitlementAttributes";
    public static final String OBJECT_TYPE_PERMISSION = "sailpoint.object.Link.permissions";
    public static final String OBJECT_TYPE_TARGET_PERMISSION = "sailpoint.object.Link.targetPermissions";

    /** Columns between the PK and {@code record_hash}, in iiq_native.kf_account_entitlement order. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("link_id"),
            ColumnSpec.text("identity_id"),
            ColumnSpec.text("identity_name"),
            ColumnSpec.text("application_id"),
            ColumnSpec.text("application_name"),
            ColumnSpec.text("native_identity"),
            ColumnSpec.text("instance"),
            ColumnSpec.text("attribute_name"),
            ColumnSpec.text("attribute_value"));

    /** Columns iiq_native.kf_account_entitlement places after {@code deleted_at}, in table order. */
    public static final List<ColumnSpec> TRAILING = List.of(
            ColumnSpec.text("type"),
            ColumnSpec.text("permission_target"),
            ColumnSpec.text("permission_rights"),
            ColumnSpec.jsonb("permission_rights_list"),
            ColumnSpec.text("permission_annotation"),
            ColumnSpec.text("permission_aggregation_source"),
            ColumnSpec.jsonb("permission_attributes"));

    public static final EntitySpec SPEC = new EntitySpec(
            "AccountEntitlement", RESOURCE, TABLE, PK, BUSINESS,
            AccountEntitlementTable::accountEntitlementId,
            AccountEntitlementTable::recordHash,
            AccountEntitlementTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, OBJECT_TYPE_ATTRIBUTE,
            TRAILING,
            AccountEntitlementTable::objectType);

    public static String accountEntitlementId(JsonNode row) {
        return CanonicalIds.accountEntitlementId(text(row, "link_id"), text(row, "type"),
                text(row, "attribute_name"), text(row, "attribute_value"), text(row, "permission_target"),
                text(row, "permission_rights"), text(row, "permission_annotation"));
    }

    /**
     * Part 1 stamps the object type by edge kind. A null type is treated as ATTRIBUTE (as Part 1's key does); a type
     * Part 1 never produces fails the row instead of being stamped with a guessed lineage.
     */
    public static String objectType(JsonNode row) {
        String type = text(row, "type");
        if (type == null || "ATTRIBUTE".equals(type)) {
            return OBJECT_TYPE_ATTRIBUTE;
        }
        return switch (type) {
            case "PERMISSION" -> OBJECT_TYPE_PERMISSION;
            case "TARGET_PERMISSION" -> OBJECT_TYPE_TARGET_PERMISSION;
            default -> throw new IllegalArgumentException("unknown account-entitlement type '" + type
                    + "' (expected ATTRIBUTE, PERMISSION or TARGET_PERMISSION)");
        };
    }

    /** Part 1 {@code NativeAccountEntitlementRepository.recordHash}: 14 fields — all but identity_name, application_id. */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("link_id", text(r, "link_id"));
        b.put("identity_id", text(r, "identity_id"));
        b.put("application_name", text(r, "application_name"));
        b.put("native_identity", text(r, "native_identity"));
        b.put("instance", text(r, "instance"));
        b.put("type", text(r, "type"));
        b.put("attribute_name", text(r, "attribute_name"));
        b.put("attribute_value", text(r, "attribute_value"));
        b.put("permission_target", text(r, "permission_target"));
        b.put("permission_rights", text(r, "permission_rights"));
        b.put("permission_rights_list", json(r, "permission_rights_list"));
        b.put("permission_annotation", text(r, "permission_annotation"));
        b.put("permission_aggregation_source", text(r, "permission_aggregation_source"));
        b.put("permission_attributes", json(r, "permission_attributes"));
        return RecordHash.of(b);
    }

    private static String ref(JsonNode row) {
        String type = text(row, "type");
        String what = (type == null || "ATTRIBUTE".equals(type))
                ? text(row, "attribute_name") + "=" + text(row, "attribute_value")
                : type + ":" + text(row, "permission_target") + "[" + text(row, "permission_rights") + "]";
        return "link " + text(row, "link_id") + " (" + text(row, "application_name") + "/"
                + text(row, "native_identity") + ") " + what;
    }
}

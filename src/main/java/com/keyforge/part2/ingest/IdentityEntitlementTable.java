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
 * {@code GET /kfagent/identity-entitlements} → {@code kf_identity_entitlement}: the identity↔entitlement
 * relationship with provenance (IIQ {@code IdentityEntitlement}), taken from Part 1's iiq_native.sql +
 * {@code NativeIdentityEntitlementRepository} / {@code NativeIdentityEntitlementRow}:
 * <ul>
 *   <li>Unlike identity-roles, this edge IS a real IIQ object and has a {@code source_id}. PK
 *       {@code entitlementid uuid} (same column NAME as kf_entitlement's PK, but it identifies the
 *       IdentityEntitlement row, not the catalog entitlement) = {@link CanonicalIds#identityEntitlementId}:
 *       canonical source_id, else the composite natural key
 *       {@code identity_id|application_name|native_identity|attribute_name|attribute_value}.</li>
 *   <li>33 business columns = the 33 response fields, same names, table order; {@code value_list} is the only
 *       {@code jsonb}; {@code source_assignable_roles} / {@code source_detected_roles} are text.</li>
 *   <li>{@code record_hash} = Part 1's 30-field subset: all fields EXCEPT {@code identity_name},
 *       {@code application_id} and {@code display_name}.</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.IdentityEntitlement}</li>
 * </ul>
 */
public final class IdentityEntitlementTable {

    private IdentityEntitlementTable() {
    }

    public static final String RESOURCE = "identity-entitlements";
    public static final String TABLE = "kf_identity_entitlement";
    public static final String PK = "entitlementid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.IdentityEntitlement";

    /** Business columns in iiq_native.kf_identity_entitlement order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("identity_id"),
            ColumnSpec.text("identity_name"),
            ColumnSpec.text("application_id"),
            ColumnSpec.text("application_name"),
            ColumnSpec.text("native_identity"),
            ColumnSpec.text("instance"),
            ColumnSpec.text("attribute_name"),
            ColumnSpec.text("attribute_value"),
            ColumnSpec.jsonb("value_list"),
            ColumnSpec.text("type"),
            ColumnSpec.text("display_name"),
            ColumnSpec.text("annotation"),
            ColumnSpec.bool("assigned"),
            ColumnSpec.bool("granted_by_role"),
            ColumnSpec.bool("allowed"),
            ColumnSpec.bool("connected"),
            ColumnSpec.text("aggregation_state"),
            ColumnSpec.text("source"),
            ColumnSpec.text("source_object"),
            ColumnSpec.text("assigner"),
            ColumnSpec.text("assignment_id"),
            ColumnSpec.text("assignment_note"),
            ColumnSpec.text("source_assignable_roles"),
            ColumnSpec.text("source_detected_roles"),
            ColumnSpec.text("certification_item_id"),
            ColumnSpec.text("pending_certification_item_id"),
            ColumnSpec.text("request_item_id"),
            ColumnSpec.text("pending_request_item_id"),
            ColumnSpec.timestamptz("start_date"),
            ColumnSpec.timestamptz("end_date"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "IdentityEntitlement", RESOURCE, TABLE, PK, BUSINESS,
            IdentityEntitlementTable::identityEntitlementId,
            IdentityEntitlementTable::recordHash,
            IdentityEntitlementTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String identityEntitlementId(JsonNode row) {
        return CanonicalIds.identityEntitlementId(text(row, "source_id"), text(row, "identity_id"),
                text(row, "application_name"), text(row, "native_identity"), text(row, "attribute_name"),
                text(row, "attribute_value"));
    }

    /**
     * Part 1 {@code NativeIdentityEntitlementRepository.recordHash}: 30 fields — all but identity_name,
     * application_id and display_name.
     */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source_id", text(r, "source_id"));
        b.put("identity_id", text(r, "identity_id"));
        b.put("application_name", text(r, "application_name"));
        b.put("native_identity", text(r, "native_identity"));
        b.put("instance", text(r, "instance"));
        b.put("attribute_name", text(r, "attribute_name"));
        b.put("attribute_value", text(r, "attribute_value"));
        b.put("value_list", json(r, "value_list"));
        b.put("type", text(r, "type"));
        b.put("annotation", text(r, "annotation"));
        b.put("assigned", bool(r, "assigned"));
        b.put("granted_by_role", bool(r, "granted_by_role"));
        b.put("allowed", bool(r, "allowed"));
        b.put("connected", bool(r, "connected"));
        b.put("aggregation_state", text(r, "aggregation_state"));
        b.put("source", text(r, "source"));
        b.put("source_object", text(r, "source_object"));
        b.put("assigner", text(r, "assigner"));
        b.put("assignment_id", text(r, "assignment_id"));
        b.put("assignment_note", text(r, "assignment_note"));
        b.put("source_assignable_roles", text(r, "source_assignable_roles"));
        b.put("source_detected_roles", text(r, "source_detected_roles"));
        b.put("certification_item_id", text(r, "certification_item_id"));
        b.put("pending_certification_item_id", text(r, "pending_certification_item_id"));
        b.put("request_item_id", text(r, "request_item_id"));
        b.put("pending_request_item_id", text(r, "pending_request_item_id"));
        b.put("start_date", instant(r, "start_date"));
        b.put("end_date", instant(r, "end_date"));
        b.put("created_at", instant(r, "created_at"));
        b.put("modified_at", instant(r, "modified_at"));
        return RecordHash.of(b);
    }

    private static String ref(JsonNode row) {
        String id = text(row, "source_id");
        if (id != null) {
            return id;
        }
        return text(row, "identity_id") + "/" + text(row, "application_name") + "/" + text(row, "native_identity")
                + "/" + text(row, "attribute_name") + "=" + text(row, "attribute_value");
    }
}

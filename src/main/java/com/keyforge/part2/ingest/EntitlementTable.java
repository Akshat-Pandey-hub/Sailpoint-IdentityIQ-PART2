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
 * {@code GET /kfagent/entitlements} → {@code kf_entitlement} (IIQ {@code ManagedAttribute}), taken from Part 1's
 * iiq_native.sql + {@code NativeManagedAttributeRepository} / {@code NativeManagedAttributeRow}:
 * <ul>
 *   <li>PK {@code entitlementid uuid} = {@link CanonicalIds#entitlementId} (not in the response)</li>
 *   <li>43 business columns = the 43 response fields, same names, table order</li>
 *   <li>{@code record_hash} = SHA-256 over Part 1's 33-field subset (see {@link #recordHash}); the
 *       response's {@code source_hash} is IIQ's own hash and is stored as a plain business column</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.ManagedAttribute}</li>
 * </ul>
 */
public final class EntitlementTable {

    private EntitlementTable() {
    }

    public static final String RESOURCE = "entitlements";
    public static final String TABLE = "kf_entitlement";
    public static final String PK = "entitlementid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.ManagedAttribute";

    /** Business columns in iiq_native.kf_entitlement order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("value"),
            ColumnSpec.text("display_name"),
            ColumnSpec.text("displayable_name"),
            ColumnSpec.text("attribute"),
            ColumnSpec.text("type"),
            ColumnSpec.text("ma_uuid"),
            ColumnSpec.text("reference_attribute"),
            ColumnSpec.text("purview"),
            ColumnSpec.text("application_id"),
            ColumnSpec.text("application_name"),
            ColumnSpec.text("instance"),
            ColumnSpec.text("native_identity"),
            ColumnSpec.bool("requestable"),
            ColumnSpec.bool("is_group"),
            ColumnSpec.bool("is_permission"),
            ColumnSpec.bool("uncorrelated"),
            ColumnSpec.bool("aggregated"),
            ColumnSpec.bool("iiq_elevated_access"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.text("description"),
            ColumnSpec.jsonb("descriptions"),
            ColumnSpec.jsonb("permissions"),
            ColumnSpec.jsonb("target_permissions"),
            ColumnSpec.jsonb("inheritance"),
            ColumnSpec.jsonb("associations"),
            ColumnSpec.jsonb("attributes"),
            ColumnSpec.jsonb("classifications"),
            ColumnSpec.jsonb("classification_names"),
            ColumnSpec.jsonb("classification_display_names"),
            ColumnSpec.text("member_attribute"),
            ColumnSpec.text("full_name"),
            ColumnSpec.bool("is_group_type"),
            ColumnSpec.bool("is_inactive"),
            ColumnSpec.bool("is_auto_promotion"),
            ColumnSpec.bool("is_differencable"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"),
            ColumnSpec.timestamptz("last_refresh"),
            ColumnSpec.timestamptz("last_target_aggregation"),
            ColumnSpec.text("source_hash"));

    public static final EntitySpec SPEC = new EntitySpec(
            "Entitlement", RESOURCE, TABLE, PK, BUSINESS,
            EntitlementTable::entitlementId,
            EntitlementTable::recordHash,
            EntitlementTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String entitlementId(JsonNode row) {
        return CanonicalIds.entitlementId(text(row, "source_id"), text(row, "name"));
    }

    /**
     * Part 1 {@code NativeManagedAttributeRepository.recordHash}: exactly these 33 fields, with Part 1's value
     * types. Deliberately EXCLUDES classifications, classification_names, classification_display_names,
     * member_attribute, full_name, is_group_type, is_inactive, is_auto_promotion, is_differencable and
     * source_hash (columns Part 1 added later without extending the hash) and all lineage fields.
     */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source_id", text(r, "source_id"));
        b.put("name", text(r, "name"));
        b.put("value", text(r, "value"));
        b.put("display_name", text(r, "display_name"));
        b.put("displayable_name", text(r, "displayable_name"));
        b.put("attribute", text(r, "attribute"));
        b.put("type", text(r, "type"));
        b.put("ma_uuid", text(r, "ma_uuid"));
        b.put("reference_attribute", text(r, "reference_attribute"));
        b.put("purview", text(r, "purview"));
        b.put("application_id", text(r, "application_id"));
        b.put("application_name", text(r, "application_name"));
        b.put("instance", text(r, "instance"));
        b.put("native_identity", text(r, "native_identity"));
        b.put("requestable", bool(r, "requestable"));
        b.put("is_group", bool(r, "is_group"));
        b.put("is_permission", bool(r, "is_permission"));
        b.put("uncorrelated", bool(r, "uncorrelated"));
        b.put("aggregated", bool(r, "aggregated"));
        b.put("iiq_elevated_access", bool(r, "iiq_elevated_access"));
        b.put("owner_id", text(r, "owner_id"));
        b.put("owner_name", text(r, "owner_name"));
        b.put("description", text(r, "description"));
        b.put("descriptions", json(r, "descriptions"));
        b.put("permissions", json(r, "permissions"));
        b.put("target_permissions", json(r, "target_permissions"));
        b.put("inheritance", json(r, "inheritance"));
        b.put("associations", json(r, "associations"));
        b.put("attributes", json(r, "attributes"));
        b.put("created_at", instant(r, "created_at"));
        b.put("modified_at", instant(r, "modified_at"));
        b.put("last_refresh", instant(r, "last_refresh"));
        b.put("last_target_aggregation", instant(r, "last_target_aggregation"));
        return RecordHash.of(b);
    }

    private static String ref(JsonNode row) {
        String id = text(row, "source_id");
        if (id != null) {
            return id;
        }
        String app = text(row, "application_name");
        String value = text(row, "value");
        return (app == null && value == null) ? "<no source_id>" : app + "/" + value;
    }
}

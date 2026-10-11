package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/applications} → {@code kf_application} (IIQ {@code Application}), taken from Part 1's
 * iiq_native.sql + {@code NativeApplicationRepository} / {@code NativeApplicationRow}:
 * <ul>
 *   <li>PK {@code applicationid uuid} = {@link CanonicalIds#applicationId} (not in the response)</li>
 *   <li>58 business columns = the 58 response fields, same names, table order ({@code score} is integer;
 *       10 {@code jsonb}; the rule/config columns are plain text)</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 58 business fields (Part 1's {@code recordHash})</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.Application}</li>
 * </ul>
 */
public final class ApplicationTable {

    private ApplicationTable() {
    }

    public static final String RESOURCE = "applications";
    public static final String TABLE = "kf_application";
    public static final String PK = "applicationid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Application";

    /** Business columns in iiq_native.kf_application order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("description"),
            ColumnSpec.text("type"),
            ColumnSpec.text("connector"),
            ColumnSpec.text("features_string"),
            ColumnSpec.text("profile_class"),
            ColumnSpec.text("proxied_name"),
            ColumnSpec.text("cluster"),
            ColumnSpec.text("icon"),
            ColumnSpec.text("aggregation_types"),
            ColumnSpec.text("before_provisioning_rule"),
            ColumnSpec.text("after_provisioning_rule"),
            ColumnSpec.text("account_schema_correlation_rule"),
            ColumnSpec.text("account_schema_customization_rule"),
            ColumnSpec.text("account_schema_creation_rule"),
            ColumnSpec.text("account_schema_refresh_rule"),
            ColumnSpec.text("application_creation_rule"),
            ColumnSpec.text("account_schema_correlation_rule_id"),
            ColumnSpec.text("account_schema_customization_rule_id"),
            ColumnSpec.text("account_schema_creation_rule_id"),
            ColumnSpec.text("account_schema_refresh_rule_id"),
            ColumnSpec.text("application_creation_rule_id"),
            ColumnSpec.integer("score"),
            ColumnSpec.bool("authoritative"),
            ColumnSpec.bool("case_insensitive"),
            ColumnSpec.bool("logical"),
            ColumnSpec.bool("composite"),
            ColumnSpec.bool("authentication_resource"),
            ColumnSpec.bool("activity_enabled"),
            ColumnSpec.bool("in_maintenance"),
            ColumnSpec.bool("manages_other_apps"),
            ColumnSpec.bool("native_change_detection_enabled"),
            ColumnSpec.bool("supports_provisioning"),
            ColumnSpec.bool("supports_account_only"),
            ColumnSpec.bool("supports_additional_accounts"),
            ColumnSpec.bool("supports_authenticate"),
            ColumnSpec.bool("supports_group_provisioning"),
            ColumnSpec.bool("supports_direct_permissions"),
            ColumnSpec.bool("sync_provisioning"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.jsonb("secondary_owners"),
            ColumnSpec.jsonb("remediators"),
            ColumnSpec.jsonb("dependencies"),
            ColumnSpec.jsonb("schemas"),
            ColumnSpec.jsonb("descriptions"),
            ColumnSpec.jsonb("attributes"),
            ColumnSpec.text("provisioning_config"),
            ColumnSpec.text("account_correlation_config"),
            ColumnSpec.text("manager_correlation_rule"),
            ColumnSpec.text("manager_correlation_filter"),
            ColumnSpec.jsonb("service_account_filter"),
            ColumnSpec.jsonb("rpa_account_filter"),
            ColumnSpec.jsonb("disable_account_filter"),
            ColumnSpec.jsonb("lock_account_filter"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "Application", RESOURCE, TABLE, PK, BUSINESS,
            ApplicationTable::applicationId,
            ApplicationTable::recordHash,
            ApplicationTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String applicationId(JsonNode row) {
        return CanonicalIds.applicationId(text(row, "source_id"), text(row, "name"));
    }

    /**
     * Part 1 {@code NativeApplicationRepository.recordHash}: every business column, read with Part 1's value
     * types (text / Boolean / Integer / compact-JSON string / Instant), lineage excluded.
     */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, BUSINESS);
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

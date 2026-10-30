package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.hash.RecordHash;
import com.keyforge.part2.id.CanonicalIds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.bool;
import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/certification-items} → {@code kf_certification_item}: each IIQ {@code CertificationItem}, one
 * reviewable line (role, entitlement exception, violation, …) and its decision. Taken from Part 1's iiq_native.sql +
 * {@code NativeCertificationItemRepository} / {@code NativeCertificationItemRow}:
 * <ul>
 *   <li>PK {@code certificationitemid uuid} = {@link CanonicalIds#certificationItemId}: the item's own canonical IIQ id,
 *       fallback {@code "native-certification-item|"} + id only (no name/identity fallback).</li>
 *   <li>Links (raw IIQ ids, no foreign keys): {@code certification_id} = the parent Certification
 *       ({@code kf_certification.source_id}); {@code entity_id} = the parent CertificationEntity
 *       ({@code kf_certification_entity.source_id}); {@code action_decision_certification_id} = the certification in
 *       which the decision was made; {@code policy_violation_id} for violation items.</li>
 *   <li>55 response fields, same names: 53 {@link #BUSINESS} columns precede {@code record_hash}; 2 {@link #TRAILING}
 *       ({@code policy_violation_id}, {@code role_assignment}) sit after {@code deleted_at}, as in iiq_native.
 *       {@code application_names} / {@code classification_names} are jsonb; 13 booleans; 8 timestamps.</li>
 *   <li>{@code record_hash} = Part 1's 20-field subset (see {@link #recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.CertificationItem}</li>
 * </ul>
 */
public final class CertificationItemTable {

    private CertificationItemTable() {
    }

    public static final String RESOURCE = "certification-items";
    public static final String TABLE = "kf_certification_item";
    public static final String PK = "certificationitemid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.CertificationItem";

    /** Columns between the PK and {@code record_hash}, in iiq_native.kf_certification_item order. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("certification_id"),
            ColumnSpec.text("entity_id"),
            ColumnSpec.text("identity"),
            ColumnSpec.text("type"),
            ColumnSpec.text("sub_type"),
            ColumnSpec.text("bundle"),
            ColumnSpec.text("bundle_assignment_id"),
            ColumnSpec.text("exception_application"),
            ColumnSpec.text("exception_attribute_name"),
            ColumnSpec.text("exception_attribute_value"),
            ColumnSpec.text("exception_permission_target"),
            ColumnSpec.text("exception_permission_right"),
            ColumnSpec.text("account_group"),
            ColumnSpec.text("phase"),
            ColumnSpec.text("summary_status"),
            ColumnSpec.timestamptz("completed"),
            ColumnSpec.timestamptz("last_decision"),
            ColumnSpec.timestamptz("expiration_date"),
            ColumnSpec.timestamptz("finished_date"),
            ColumnSpec.bool("iiq_elevated_access"),
            ColumnSpec.bool("reviewed"),
            ColumnSpec.bool("delegated"),
            ColumnSpec.bool("acted_upon"),
            ColumnSpec.bool("historical"),
            ColumnSpec.bool("expired"),
            ColumnSpec.text("target_id"),
            ColumnSpec.text("target_name"),
            ColumnSpec.text("short_description"),
            ColumnSpec.text("violation_summary"),
            ColumnSpec.jsonb("application_names"),
            ColumnSpec.jsonb("classification_names"),
            ColumnSpec.text("action_status"),
            ColumnSpec.timestamptz("action_decision_date"),
            ColumnSpec.text("action_decision_certification_id"),
            ColumnSpec.text("action_remediation_action"),
            ColumnSpec.text("action_actor_name"),
            ColumnSpec.text("action_actor_display_name"),
            ColumnSpec.text("action_comments"),
            ColumnSpec.text("action_completion_comments"),
            ColumnSpec.text("action_owner_name"),
            ColumnSpec.timestamptz("action_mitigation_expiration"),
            ColumnSpec.bool("action_is_approved"),
            ColumnSpec.bool("action_is_remediation"),
            ColumnSpec.bool("action_is_mitigation"),
            ColumnSpec.bool("action_is_delegation"),
            ColumnSpec.bool("action_is_revoke_account"),
            ColumnSpec.bool("action_is_auto_decision"),
            ColumnSpec.bool("action_is_bulk_certified"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    /** Columns iiq_native.kf_certification_item places after {@code deleted_at}, in table order. */
    public static final List<ColumnSpec> TRAILING = List.of(
            ColumnSpec.text("policy_violation_id"),
            ColumnSpec.text("role_assignment"));

    public static final EntitySpec SPEC = new EntitySpec(
            "CertificationItem", RESOURCE, TABLE, PK, BUSINESS,
            CertificationItemTable::certificationItemId,
            CertificationItemTable::recordHash,
            CertificationItemTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE,
            TRAILING);

    public static String certificationItemId(JsonNode row) {
        // no IIQ id: a uuid from the full content, never a shared substitute or a refused row
        return CanonicalIds.bySourceIdOrContent(row, "native-certification-item", () -> CanonicalIds.certificationItemId(text(row, "source_id")));
    }

    /**
     * Part 1 {@code NativeCertificationItemRepository.recordHash}: 20 fields — source_id, certification_id, entity_id,
     * identity, type, bundle, exception_application, exception_attribute_name, exception_attribute_value,
     * summary_status, completed, last_decision, action_status, action_decision_date, action_actor_name,
     * action_comments, action_remediation_action, iiq_elevated_access, policy_violation_id, role_assignment.
     */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source_id", text(r, "source_id"));
        b.put("certification_id", text(r, "certification_id"));
        b.put("entity_id", text(r, "entity_id"));
        b.put("identity", text(r, "identity"));
        b.put("type", text(r, "type"));
        b.put("bundle", text(r, "bundle"));
        b.put("exception_application", text(r, "exception_application"));
        b.put("exception_attribute_name", text(r, "exception_attribute_name"));
        b.put("exception_attribute_value", text(r, "exception_attribute_value"));
        b.put("summary_status", text(r, "summary_status"));
        b.put("completed", instant(r, "completed"));
        b.put("last_decision", instant(r, "last_decision"));
        b.put("action_status", text(r, "action_status"));
        b.put("action_decision_date", instant(r, "action_decision_date"));
        b.put("action_actor_name", text(r, "action_actor_name"));
        b.put("action_comments", text(r, "action_comments"));
        b.put("action_remediation_action", text(r, "action_remediation_action"));
        b.put("iiq_elevated_access", bool(r, "iiq_elevated_access"));
        b.put("policy_violation_id", text(r, "policy_violation_id"));
        b.put("role_assignment", text(r, "role_assignment"));
        return RecordHash.of(b);
    }

    private static String ref(JsonNode row) {
        return "certification item " + text(row, "source_id") + " (" + text(row, "type") + ", entity " + text(row, "entity_id")
                + ", certification " + text(row, "certification_id") + ")";
    }
}

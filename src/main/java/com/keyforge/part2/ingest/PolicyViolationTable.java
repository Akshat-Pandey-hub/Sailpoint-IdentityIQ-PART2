package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/policy-violations} → {@code kf_violation} (IIQ {@code PolicyViolation}), taken from Part 1's
 * iiq_native.sql + {@code NativeViolationRepository} / {@code NativeViolationRow}:
 * <ul>
 *   <li>PK {@code violationid uuid} = {@link CanonicalIds#violationId}: canonical source_id, fallback
 *       {@code "native-violation|"} + id (name when the id is null).</li>
 *   <li>Links (raw IIQ ids, no foreign keys): {@code policy_id} / {@code policy_name} = the violated Policy
 *       ({@code kf_policy.source_id}); {@code constraint_id} / {@code constraint_name} = the broken rule
 *       ({@code kf_policy_constraint.source_id}); {@code identity_id} / {@code identity_name} = the violator.</li>
 *   <li>19 business columns = the 19 response fields, same names, table order: {@code relevant_apps},
 *       {@code violating_entitlements}, {@code arguments} jsonb; {@code active} boolean; 2 timestamps; the SoD role sets
 *       ({@code left_bundles} / {@code right_bundles}) and remediation markings are TEXT in Part 1 and stay text.</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 19 business fields (Part 1's {@code recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.PolicyViolation}</li>
 * </ul>
 */
public final class PolicyViolationTable {

    private PolicyViolationTable() {
    }

    public static final String RESOURCE = "policy-violations";
    public static final String TABLE = "kf_violation";
    public static final String PK = "violationid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.PolicyViolation";

    /** Business columns in iiq_native.kf_violation order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("identity_id"),
            ColumnSpec.text("identity_name"),
            ColumnSpec.text("policy_id"),
            ColumnSpec.text("policy_name"),
            ColumnSpec.text("constraint_id"),
            ColumnSpec.text("constraint_name"),
            ColumnSpec.text("status"),
            ColumnSpec.bool("active"),
            ColumnSpec.text("left_bundles"),
            ColumnSpec.text("right_bundles"),
            ColumnSpec.text("entitlements_marked_for_remediation"),
            ColumnSpec.text("bundles_marked_for_remediation"),
            ColumnSpec.jsonb("relevant_apps"),
            ColumnSpec.jsonb("violating_entitlements"),
            ColumnSpec.jsonb("arguments"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "PolicyViolation", RESOURCE, TABLE, PK, BUSINESS,
            PolicyViolationTable::violationId,
            PolicyViolationTable::recordHash,
            PolicyViolationTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String violationId(JsonNode row) {
        // no IIQ id: a uuid from the full content, never a shared substitute or a refused row
        return CanonicalIds.bySourceIdOrContent(row, "native-violation", () -> CanonicalIds.violationId(text(row, "source_id"), text(row, "name")));
    }

    /** Part 1 {@code NativeViolationRepository.recordHash}: every business column, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, BUSINESS);
    }

    private static String ref(JsonNode row) {
        return "violation " + text(row, "name") + " (source_id=" + text(row, "source_id") + ", policy "
                + text(row, "policy_name") + ", constraint " + text(row, "constraint_name") + ")";
    }
}

package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/policies} → {@code kf_policy} (IIQ {@code Policy}), taken from Part 1's iiq_native.sql +
 * {@code NativePolicyRepository} / {@code NativePolicyRow}:
 * <ul>
 *   <li>PK {@code policyid uuid} = {@link CanonicalIds#policyId}: canonical source_id, fallback
 *       {@code deterministicUuid("native-policy|"+id)} (name only when source_id is null).</li>
 *   <li>21 response fields, same names: 12 {@link #BUSINESS} columns precede {@code record_hash}; 9 {@link #TRAILING}
 *       columns ({@code state} … {@code is_template}) sit after {@code deleted_at} in iiq_native (Part 1 added them with
 *       {@code ALTER TABLE}).</li>
 *   <li>{@code descriptions} is the only {@code jsonb}. {@code violation_rule}, {@code violation_workflow},
 *       {@code signature} and {@code certification_actions} are TEXT in Part 1 (read with {@code text()};
 *       certification_actions is a comma-joined string), so they stay text. {@code constraint_count} is integer,
 *       {@code is_template} boolean.</li>
 *   <li>Policy-specific relationships stay as raw text, exactly as in Part 1: the violation owner
 *       ({@code violation_owner_id/_name/_type}) and owner rule ({@code violation_owner_rule_id/_name}); the policy's
 *       constraints are their own entity (policy-constraints), only {@code constraint_count} is here.</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 21 fields (Part 1's {@code recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.Policy}</li>
 * </ul>
 */
public final class PolicyTable {

    private PolicyTable() {
    }

    public static final String RESOURCE = "policies";
    public static final String TABLE = "kf_policy";
    public static final String PK = "policyid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Policy";

    /** Columns between the PK and {@code record_hash}, in iiq_native.kf_policy order. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("type"),
            ColumnSpec.text("type_key"),
            ColumnSpec.text("description"),
            ColumnSpec.jsonb("descriptions"),
            ColumnSpec.text("executor"),
            ColumnSpec.text("violation_owner_id"),
            ColumnSpec.text("violation_owner_name"),
            ColumnSpec.integer("constraint_count"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    /** Columns iiq_native.kf_policy places after {@code deleted_at}, in table order. */
    public static final List<ColumnSpec> TRAILING = List.of(
            ColumnSpec.text("state"),
            ColumnSpec.text("violation_rule"),
            ColumnSpec.text("violation_workflow"),
            ColumnSpec.text("signature"),
            ColumnSpec.text("certification_actions"),
            ColumnSpec.text("violation_owner_type"),
            ColumnSpec.text("violation_owner_rule_id"),
            ColumnSpec.text("violation_owner_rule_name"),
            ColumnSpec.bool("is_template"));

    public static final EntitySpec SPEC = new EntitySpec(
            "Policy", RESOURCE, TABLE, PK, BUSINESS,
            PolicyTable::policyId,
            PolicyTable::recordHash,
            PolicyTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE,
            TRAILING);

    public static String policyId(JsonNode row) {
        return CanonicalIds.policyId(text(row, "source_id"), text(row, "name"));
    }

    /** Part 1 {@code NativePolicyRepository.recordHash}: all 21 fields, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, SPEC.allBusiness());
    }

    private static String ref(JsonNode row) {
        String id = text(row, "source_id");
        return "policy " + text(row, "name") + " (" + (id == null ? "<no source_id>" : id) + ")";
    }
}

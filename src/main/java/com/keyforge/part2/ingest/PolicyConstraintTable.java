package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.hash.RecordHash;
import com.keyforge.part2.id.CanonicalIds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.integer;
import static com.keyforge.part2.hash.HashValues.json;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/policy-constraints} → {@code kf_policy_constraint}: each rule (IIQ {@code BaseConstraint}, e.g. an
 * SOD constraint) inside a Policy. Taken from Part 1's iiq_native.sql + {@code NativePolicyConstraintRepository} /
 * {@code NativePolicyConstraintRow}:
 * <ul>
 *   <li>PK {@code policyconstraintid uuid} = {@link CanonicalIds#policyConstraintId}: the constraint's own canonical IIQ
 *       id, else the composite {@code policy_id|(source_id or name)|constraint_type}.</li>
 *   <li>Parent link: {@code policy_id} = the parent Policy's RAW IIQ id ({@code kf_policy.source_id}), plus
 *       {@code policy_name}; {@code kf_policy.constraint_count} counts these rows. Policy violations reference the
 *       constraint they broke (policy-violations entity); this table has no violation columns.</li>
 *   <li>18 business columns = the 18 response fields, same names, table order: {@code left_bundles},
 *       {@code right_bundles}, {@code selectors}, {@code arguments} jsonb; {@code weight} / {@code selector_count}
 *       integer; 2 timestamps.</li>
 *   <li>{@code record_hash} = Part 1's 16-field subset: every field except {@code policy_name} and
 *       {@code violation_owner_name}.</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.BaseConstraint}</li>
 * </ul>
 */
public final class PolicyConstraintTable {

    private PolicyConstraintTable() {
    }

    public static final String RESOURCE = "policy-constraints";
    public static final String TABLE = "kf_policy_constraint";
    public static final String PK = "policyconstraintid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.BaseConstraint";

    /** Business columns in iiq_native.kf_policy_constraint order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("policy_id"),
            ColumnSpec.text("policy_name"),
            ColumnSpec.text("name"),
            ColumnSpec.text("description"),
            ColumnSpec.text("constraint_type"),
            ColumnSpec.integer("weight"),
            ColumnSpec.text("compensating_control"),
            ColumnSpec.text("violation_owner_id"),
            ColumnSpec.text("violation_owner_name"),
            ColumnSpec.text("violation_owner_type"),
            ColumnSpec.jsonb("left_bundles"),
            ColumnSpec.jsonb("right_bundles"),
            ColumnSpec.jsonb("selectors"),
            ColumnSpec.integer("selector_count"),
            ColumnSpec.jsonb("arguments"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "PolicyConstraint", RESOURCE, TABLE, PK, BUSINESS,
            PolicyConstraintTable::policyConstraintId,
            PolicyConstraintTable::recordHash,
            PolicyConstraintTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String policyConstraintId(JsonNode row) {
        return CanonicalIds.policyConstraintId(text(row, "source_id"), text(row, "policy_id"), text(row, "name"),
                text(row, "constraint_type"));
    }

    /** Part 1 {@code NativePolicyConstraintRepository.recordHash}: 16 fields — all but policy_name, violation_owner_name. */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source_id", text(r, "source_id"));
        b.put("policy_id", text(r, "policy_id"));
        b.put("name", text(r, "name"));
        b.put("description", text(r, "description"));
        b.put("constraint_type", text(r, "constraint_type"));
        b.put("weight", integer(r, "weight"));
        b.put("compensating_control", text(r, "compensating_control"));
        b.put("violation_owner_id", text(r, "violation_owner_id"));
        b.put("violation_owner_type", text(r, "violation_owner_type"));
        b.put("left_bundles", json(r, "left_bundles"));
        b.put("right_bundles", json(r, "right_bundles"));
        b.put("selectors", json(r, "selectors"));
        b.put("selector_count", integer(r, "selector_count"));
        b.put("arguments", json(r, "arguments"));
        b.put("created_at", instant(r, "created_at"));
        b.put("modified_at", instant(r, "modified_at"));
        return RecordHash.of(b);
    }

    private static String ref(JsonNode row) {
        return "constraint " + text(row, "name") + " of policy " + text(row, "policy_name") + " (source_id="
                + text(row, "source_id") + ")";
    }
}

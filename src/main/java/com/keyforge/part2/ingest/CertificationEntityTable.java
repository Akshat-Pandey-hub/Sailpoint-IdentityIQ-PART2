package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.hash.RecordHash;
import com.keyforge.part2.id.CanonicalIds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/certification-entities} → {@code kf_certification_entity}: each IIQ {@code CertificationEntity},
 * i.e. one identity / account / account-group being reviewed inside a certification. Taken from Part 1's iiq_native.sql
 * + {@code NativeCertificationEntityRepository} / {@code NativeCertificationEntityRow}:
 * <ul>
 *   <li>PK {@code certificationentityid uuid} = {@link CanonicalIds#certificationEntityId}: the entity's OWN canonical
 *       IIQ id; fallback {@code "native-certification-entity|"} + id, or + the entity's {@code identity} when the id is
 *       null. These rules differ from the certification header's (which falls back to the header name).</li>
 *   <li>Parent link: {@code certification_id} = the RAW IIQ id of the parent Certification
 *       ({@code kf_certification.source_id}), not the header's uuid key. The entity's own raw {@code source_id} is what
 *       its decision items (certification-items) reference.</li>
 *   <li>32 business columns = the 32 response fields, same names, table order, all scalar: {@code entity_delegated}
 *       boolean, {@code composite_score} integer, 4 timestamps, the rest text.</li>
 *   <li>{@code record_hash} = Part 1's 11-field subset (see {@link #recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.CertificationEntity}</li>
 * </ul>
 */
public final class CertificationEntityTable {

    private CertificationEntityTable() {
    }

    public static final String RESOURCE = "certification-entities";
    public static final String TABLE = "kf_certification_entity";
    public static final String PK = "certificationentityid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.CertificationEntity";

    /** Business columns in iiq_native.kf_certification_entity order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("certification_id"),
            ColumnSpec.text("identity"),
            ColumnSpec.text("application"),
            ColumnSpec.text("native_identity"),
            ColumnSpec.text("account_group"),
            ColumnSpec.text("first_name"),
            ColumnSpec.text("last_name"),
            ColumnSpec.text("full_name"),
            ColumnSpec.text("reference_attribute"),
            ColumnSpec.text("schema_object_type"),
            ColumnSpec.text("snapshot_id"),
            ColumnSpec.text("pending_certification"),
            ColumnSpec.text("type"),
            ColumnSpec.text("summary_status"),
            ColumnSpec.bool("entity_delegated"),
            ColumnSpec.text("entity_delegation_status"),
            ColumnSpec.integer("composite_score"),
            ColumnSpec.text("target_id"),
            ColumnSpec.text("target_name"),
            ColumnSpec.text("target_display_name"),
            ColumnSpec.timestamptz("completed"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.text("action_status"),
            ColumnSpec.timestamptz("action_decision_date"),
            ColumnSpec.text("action_remediation_action"),
            ColumnSpec.text("action_actor_name"),
            ColumnSpec.text("action_actor_display_name"),
            ColumnSpec.text("action_comments"));

    public static final EntitySpec SPEC = new EntitySpec(
            "CertificationEntity", RESOURCE, TABLE, PK, BUSINESS,
            CertificationEntityTable::certificationEntityId,
            CertificationEntityTable::recordHash,
            CertificationEntityTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String certificationEntityId(JsonNode row) {
        return CanonicalIds.certificationEntityId(text(row, "source_id"), text(row, "identity"));
    }

    /**
     * Part 1 {@code NativeCertificationEntityRepository.recordHash}: 11 fields — source_id, certification_id, identity,
     * application, native_identity, type, summary_status, completed, action_status, action_decision_date,
     * action_actor_name. The other 21 response fields do not affect the hash.
     */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source_id", text(r, "source_id"));
        b.put("certification_id", text(r, "certification_id"));
        b.put("identity", text(r, "identity"));
        b.put("application", text(r, "application"));
        b.put("native_identity", text(r, "native_identity"));
        b.put("type", text(r, "type"));
        b.put("summary_status", text(r, "summary_status"));
        b.put("completed", instant(r, "completed"));
        b.put("action_status", text(r, "action_status"));
        b.put("action_decision_date", instant(r, "action_decision_date"));
        b.put("action_actor_name", text(r, "action_actor_name"));
        return RecordHash.of(b);
    }

    private static String ref(JsonNode row) {
        return "certification entity " + text(row, "identity") + " (source_id=" + text(row, "source_id")
                + ", certification " + text(row, "certification_id") + ")";
    }
}

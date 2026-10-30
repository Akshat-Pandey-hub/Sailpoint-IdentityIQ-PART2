package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.hash.RecordHash;
import com.keyforge.part2.id.CanonicalIds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.bool;
import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.integer;
import static com.keyforge.part2.hash.HashValues.json;
import static com.keyforge.part2.hash.HashValues.jsonText;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/certifications} → {@code kf_certification}: the certification HEADER (IIQ {@code Certification},
 * one access review), taken from Part 1's iiq_native.sql + {@code NativeCertificationRepository} / mapper:
 * <ul>
 *   <li>PK {@code certificationid uuid} = {@link CanonicalIds#certificationId}: canonical source_id, fallback
 *       {@code "native-certification|"} + id (name when the id is null).</li>
 *   <li>Header only. Its children are separate entities and tables: the certified entities (certification-entities)
 *       and their decision items (certification-items); completed certifications archived by IIQ are the
 *       certification-archives entity. Here only the counts ({@code total/completed/open_items},
 *       {@code total/completed/open_entities}, {@code percent_complete}) and the raw links are kept:
 *       {@code certification_group_id/_name} (campaign), {@code certification_definition_id}, {@code parent_id},
 *       {@code group_definition_id/_name}, {@code application_id}, {@code task_schedule_id}, {@code trigger_id}.</li>
 *   <li>43 response fields, same names: 39 {@link #BUSINESS} columns before {@code record_hash}; 4 {@link #TRAILING}
 *       ({@code approver_rule}, {@code automatic_closing_date}, {@code allowed_statuses}, {@code tags}) after
 *       {@code deleted_at}, as in iiq_native. {@code certifiers} / {@code sign_off_history} are jsonb;
 *       {@code allowed_statuses} / {@code tags} are TEXT holding IIQ's compact JSON array (the KF Agent returns them
 *       re-hydrated, Part 2 stores the compact text again: {@link ColumnSpec.Kind#JSON_TEXT});
 *       {@code automatic_closing_date} is text, not a timestamp.</li>
 *   <li>{@code record_hash} = Part 1's 26-field subset (see {@link #recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.Certification}</li>
 * </ul>
 */
public final class CertificationTable {

    private CertificationTable() {
    }

    public static final String RESOURCE = "certifications";
    public static final String TABLE = "kf_certification";
    public static final String PK = "certificationid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Certification";

    /** Columns between the PK and {@code record_hash}, in iiq_native.kf_certification order. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("certification_name"),
            ColumnSpec.text("short_name"),
            ColumnSpec.text("type"),
            ColumnSpec.text("phase"),
            ColumnSpec.text("comments"),
            ColumnSpec.text("creator"),
            ColumnSpec.text("manager"),
            ColumnSpec.text("certification_group_id"),
            ColumnSpec.text("certification_group_name"),
            ColumnSpec.text("certification_definition_id"),
            ColumnSpec.text("group_definition_id"),
            ColumnSpec.text("group_definition_name"),
            ColumnSpec.text("application_id"),
            ColumnSpec.text("task_schedule_id"),
            ColumnSpec.text("trigger_id"),
            ColumnSpec.text("parent_id"),
            ColumnSpec.bool("complete"),
            ColumnSpec.bool("expired"),
            ColumnSpec.bool("continuous"),
            ColumnSpec.bool("electronically_signed"),
            ColumnSpec.timestamptz("signed"),
            ColumnSpec.timestamptz("finished"),
            ColumnSpec.timestamptz("activated"),
            ColumnSpec.timestamptz("expiration"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"),
            ColumnSpec.integer("total_items"),
            ColumnSpec.integer("completed_items"),
            ColumnSpec.integer("open_items"),
            ColumnSpec.integer("total_entities"),
            ColumnSpec.integer("completed_entities"),
            ColumnSpec.integer("open_entities"),
            ColumnSpec.integer("percent_complete"),
            ColumnSpec.jsonb("certifiers"),
            ColumnSpec.jsonb("sign_off_history"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"));

    /** Columns iiq_native.kf_certification places after {@code deleted_at}, in table order. */
    public static final List<ColumnSpec> TRAILING = List.of(
            ColumnSpec.text("approver_rule"),
            ColumnSpec.text("automatic_closing_date"),
            ColumnSpec.jsonText("allowed_statuses"),
            ColumnSpec.jsonText("tags"));

    public static final EntitySpec SPEC = new EntitySpec(
            "Certification", RESOURCE, TABLE, PK, BUSINESS,
            CertificationTable::certificationId,
            CertificationTable::recordHash,
            CertificationTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE,
            TRAILING);

    public static String certificationId(JsonNode row) {
        // no IIQ id: a uuid from the full content, never a shared substitute or a refused row
        return CanonicalIds.bySourceIdOrContent(row, "native-certification", () -> CanonicalIds.certificationId(text(row, "source_id"), text(row, "name")));
    }

    /**
     * Part 1 {@code NativeCertificationRepository.recordHash}: 26 fields. It EXCLUDES certification_name, short_name,
     * certification_group_name, group_definition_id/_name, application_id, task_schedule_id, trigger_id, parent_id,
     * continuous, electronically_signed, created_at, modified_at, total/completed/open_entities and owner_name.
     */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source_id", text(r, "source_id"));
        b.put("name", text(r, "name"));
        b.put("type", text(r, "type"));
        b.put("phase", text(r, "phase"));
        b.put("comments", text(r, "comments"));
        b.put("creator", text(r, "creator"));
        b.put("manager", text(r, "manager"));
        b.put("certification_group_id", text(r, "certification_group_id"));
        b.put("certification_definition_id", text(r, "certification_definition_id"));
        b.put("complete", bool(r, "complete"));
        b.put("expired", bool(r, "expired"));
        b.put("signed", instant(r, "signed"));
        b.put("finished", instant(r, "finished"));
        b.put("activated", instant(r, "activated"));
        b.put("expiration", instant(r, "expiration"));
        b.put("total_items", integer(r, "total_items"));
        b.put("completed_items", integer(r, "completed_items"));
        b.put("open_items", integer(r, "open_items"));
        b.put("percent_complete", integer(r, "percent_complete"));
        b.put("certifiers", json(r, "certifiers"));
        b.put("sign_off_history", json(r, "sign_off_history"));
        b.put("approver_rule", text(r, "approver_rule"));
        b.put("automatic_closing_date", text(r, "automatic_closing_date"));
        b.put("allowed_statuses", jsonText(r, "allowed_statuses"));
        b.put("tags", jsonText(r, "tags"));
        b.put("owner_id", text(r, "owner_id"));
        return RecordHash.of(b);
    }

    private static String ref(JsonNode row) {
        return "certification " + text(row, "name") + " (source_id=" + text(row, "source_id") + ")";
    }
}

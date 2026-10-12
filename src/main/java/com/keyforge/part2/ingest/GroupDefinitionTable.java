package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/group-definitions} → {@code kf_group_definition} (IIQ {@code GroupDefinition}: Populations and
 * Groups in ONE table, told apart by {@code type} GROUP / POPULATION plus the raw {@code factory_*} evidence), taken from
 * Part 1's iiq_native.sql + {@code NativeGroupDefinitionRepository} / {@code NativeGroupDefinitionRow}:
 * <ul>
 *   <li>PK {@code groupid uuid} = canonical source_id, fallback {@code deterministicUuid("native-groupdefinition|"+id)}
 *       (name only when source_id is null).</li>
 *   <li>15 response fields, same names and order as the columns before {@code record_hash}: {@code is_private},
 *       {@code indexed}, {@code null_group}, {@code name_unique} boolean; {@code last_refresh}, {@code created_at},
 *       {@code modified_at} timestamps; {@code filter_expression} text.</li>
 *   <li>Relationships stay raw text, exactly as in Part 1: {@code factory_id} / {@code factory_name} (the GroupFactory
 *       that generated a GROUP), {@code owner_id} / {@code owner_name} (owning identity).</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 15 fields (Part 1's {@code recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.GroupDefinition}</li>
 * </ul>
 * Part 1's DB extraction also runs a soft-delete sweep after a full scan; Part 2's engine does not sweep yet.
 */
public final class GroupDefinitionTable {

    private GroupDefinitionTable() {
    }

    public static final String RESOURCE = "group-definitions";
    public static final String TABLE = "kf_group_definition";
    public static final String PK = "groupid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.GroupDefinition";

    /** Columns between the PK and {@code record_hash}, in iiq_native.kf_group_definition order. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("type"),
            ColumnSpec.text("factory_id"),
            ColumnSpec.text("factory_name"),
            ColumnSpec.text("filter_expression"),
            ColumnSpec.bool("is_private"),
            ColumnSpec.bool("indexed"),
            ColumnSpec.bool("null_group"),
            ColumnSpec.bool("name_unique"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.timestamptz("last_refresh"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "GroupDefinition", RESOURCE, TABLE, PK, BUSINESS,
            GroupDefinitionTable::groupId,
            GroupDefinitionTable::recordHash,
            GroupDefinitionTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    /** Part 1 {@code NativeGroupDefinitionRepository.canonicalGroupId}. */
    public static String groupId(JsonNode row) {
        String sourceId = text(row, "source_id");
        String id = CanonicalIds.canonicalUuid(sourceId);
        if (id == null) {
            id = CanonicalIds.deterministicUuid("native-groupdefinition|" + (sourceId == null ? text(row, "name") : sourceId));
        }
        return id;
    }

    /** Part 1 {@code NativeGroupDefinitionRepository.recordHash}: all 15 fields, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, BUSINESS);
    }

    private static String ref(JsonNode row) {
        String id = text(row, "source_id");
        return "group definition " + text(row, "name") + " (" + (id == null ? "<no source_id>" : id) + ")";
    }
}

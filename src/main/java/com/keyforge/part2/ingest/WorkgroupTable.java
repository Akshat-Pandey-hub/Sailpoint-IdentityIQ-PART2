package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/workgroups} → {@code kf_workgroup} (IIQ {@code Identity} with {@code workgroup=true}),
 * taken from Part 1's iiq_native.sql + {@code NativeWorkgroupRepository} / {@code NativeWorkgroupRow}:
 * <ul>
 *   <li>PK {@code workgroupid uuid} = {@link CanonicalIds#workgroupId} (not in the response)</li>
 *   <li>16 business columns = the 16 response fields, same names, table order (2 {@code jsonb},
 *       2 booleans, 2 timestamps)</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 16 business fields (Part 1's {@code recordHash})</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.Identity} — a
 *       workgroup IS an IIQ Identity; Part 1 does not use a separate workgroup class name</li>
 * </ul>
 */
public final class WorkgroupTable {

    private WorkgroupTable() {
    }

    public static final String RESOURCE = "workgroups";
    public static final String TABLE = "kf_workgroup";
    public static final String PK = "workgroupid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Identity";

    /** Business columns in iiq_native.kf_workgroup order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("display_name"),
            ColumnSpec.text("displayable_name"),
            ColumnSpec.text("email"),
            ColumnSpec.text("type"),
            ColumnSpec.text("description"),
            ColumnSpec.text("notification_option"),
            ColumnSpec.bool("inactive"),
            ColumnSpec.bool("is_workgroup"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.jsonb("capabilities"),
            ColumnSpec.jsonb("attributes"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "Workgroup", RESOURCE, TABLE, PK, BUSINESS,
            WorkgroupTable::workgroupId,
            WorkgroupTable::recordHash,
            WorkgroupTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String workgroupId(JsonNode row) {
        return CanonicalIds.byIdNaturalKeyOrContent(row, "native-workgroup", () -> CanonicalIds.workgroupId(text(row, "source_id"), text(row, "name")), "name");
    }

    /** Part 1 {@code NativeWorkgroupRepository.recordHash}: every business column, lineage excluded. */
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

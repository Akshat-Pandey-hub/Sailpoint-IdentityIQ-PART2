package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/identity-request-items} → {@code kf_identity_request_item} (IIQ {@code IdentityRequestItem}),
 * taken from Part 1's iiq_native.sql + {@code NativeIdentityRequestItemRepository} / {@code NativeIdentityRequestParser.toItem}:
 * <ul>
 *   <li>PK {@code identityrequestitemid uuid} = {@link CanonicalIds#identityRequestItemId}: the ITEM's own IIQ id
 *       ({@code source_id}), with Part 1's name-based fallback. The parent request is NOT the key.</li>
 *   <li>Parent link: {@code request_source_id} (raw IIQ IdentityRequest id; Part 1 already back-fills it from the
 *       parent before the response is built) and {@code request_name} (the parent's request number). Both stay
 *       text. There is no foreign key, as in iiq_native; the request's canonical key is
 *       {@code canonicalUuid(request_source_id)} if a join is needed.</li>
 *   <li>33 business columns = the 33 response fields, same names, table order: all scalar (no jsonb);
 *       {@code retries} is integer; 9 booleans; 4 timestamps.</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 33 business fields (Part 1's {@code recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.IdentityRequestItem}</li>
 * </ul>
 */
public final class IdentityRequestItemTable {

    private IdentityRequestItemTable() {
    }

    public static final String RESOURCE = "identity-request-items";
    public static final String TABLE = "kf_identity_request_item";
    public static final String PK = "identityrequestitemid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.IdentityRequestItem";

    /** Business columns in iiq_native.kf_identity_request_item order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("request_source_id"),
            ColumnSpec.text("request_name"),
            ColumnSpec.text("application"),
            ColumnSpec.text("attribute_name"),
            ColumnSpec.text("attribute_value"),
            ColumnSpec.text("operation"),
            ColumnSpec.text("managed_attribute_type"),
            ColumnSpec.text("assignment_id"),
            ColumnSpec.text("native_identity"),
            ColumnSpec.text("instance"),
            ColumnSpec.text("approver_name"),
            ColumnSpec.text("approval_state"),
            ColumnSpec.bool("approved"),
            ColumnSpec.bool("approval_complete"),
            ColumnSpec.bool("rejected"),
            ColumnSpec.text("provisioning_state"),
            ColumnSpec.text("provisioning_engine"),
            ColumnSpec.text("provisioning_request_id"),
            ColumnSpec.bool("provisioning_complete"),
            ColumnSpec.bool("provisioning_failed"),
            ColumnSpec.text("compilation_status"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.text("requester_comments"),
            ColumnSpec.bool("expansion"),
            ColumnSpec.text("expansion_cause"),
            ColumnSpec.text("expansion_info"),
            ColumnSpec.integer("retries"),
            ColumnSpec.bool("iiq"),
            ColumnSpec.timestamptz("start_date"),
            ColumnSpec.timestamptz("end_date"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "IdentityRequestItem", RESOURCE, TABLE, PK, BUSINESS,
            IdentityRequestItemTable::identityRequestItemId,
            IdentityRequestItemTable::recordHash,
            IdentityRequestItemTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String identityRequestItemId(JsonNode row) {
        // no IIQ id: a uuid from the full content, never a shared substitute or a refused row
        return CanonicalIds.bySourceIdOrContent(row, "native-identity-request-item", () -> CanonicalIds.identityRequestItemId(text(row, "source_id")));
    }

    /** Part 1 {@code NativeIdentityRequestItemRepository.recordHash}: every business column, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, BUSINESS);
    }

    private static String ref(JsonNode row) {
        return "item " + text(row, "source_id") + " of request " + text(row, "request_name")
                + " (" + text(row, "operation") + " " + text(row, "application") + " " + text(row, "attribute_name")
                + "=" + text(row, "attribute_value") + ")";
    }
}

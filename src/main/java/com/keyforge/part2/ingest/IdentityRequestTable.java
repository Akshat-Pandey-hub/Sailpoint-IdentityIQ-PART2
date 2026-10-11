package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/identity-requests} → {@code kf_identity_request} (IIQ {@code IdentityRequest}), taken from
 * Part 1's iiq_native.sql + {@code NativeIdentityRequestRepository} / {@code NativeIdentityRequestRow}:
 * <ul>
 *   <li>PK {@code identityrequestid uuid} = {@link CanonicalIds#identityRequestId}: canonical source_id with NO
 *       fallback. A request without a usable IIQ id fails its row, exactly as Part 1 refuses it.
 *       {@code name} is IIQ's request number (e.g. {@code 0000000008}); it is data, not the key.</li>
 *   <li>34 business columns = the 34 response fields, same names, table order: {@code errors} is the only
 *       {@code jsonb}; {@code item_count} / {@code approval_count} are integer; 8 booleans; 4 timestamps.</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 34 business fields (Part 1's {@code recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.IdentityRequest}</li>
 * </ul>
 * The request's items and approvals are separate entities (own endpoints and tables) and are not in this response.
 */
public final class IdentityRequestTable {

    private IdentityRequestTable() {
    }

    public static final String RESOURCE = "identity-requests";
    public static final String TABLE = "kf_identity_request";
    public static final String PK = "identityrequestid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.IdentityRequest";

    /** Business columns in iiq_native.kf_identity_request order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("type"),
            ColumnSpec.text("user_friendly_type"),
            ColumnSpec.text("state"),
            ColumnSpec.text("source"),
            ColumnSpec.text("source_object"),
            ColumnSpec.text("completion_status"),
            ColumnSpec.text("execution_status"),
            ColumnSpec.text("priority"),
            ColumnSpec.text("requester_id"),
            ColumnSpec.text("requester_display_name"),
            ColumnSpec.text("target_id"),
            ColumnSpec.text("target_display_name"),
            ColumnSpec.text("external_ticket_id"),
            ColumnSpec.text("process_id"),
            ColumnSpec.text("task_result_id"),
            ColumnSpec.bool("executing"),
            ColumnSpec.bool("failure"),
            ColumnSpec.bool("rejected"),
            ColumnSpec.bool("successful"),
            ColumnSpec.bool("terminated"),
            ColumnSpec.bool("incomplete"),
            ColumnSpec.bool("iiq_only"),
            ColumnSpec.bool("provisioning_complete"),
            ColumnSpec.timestamptz("end_date"),
            ColumnSpec.timestamptz("verified"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.jsonb("errors"),
            ColumnSpec.integer("item_count"),
            ColumnSpec.integer("approval_count"));

    public static final EntitySpec SPEC = new EntitySpec(
            "IdentityRequest", RESOURCE, TABLE, PK, BUSINESS,
            IdentityRequestTable::identityRequestId,
            IdentityRequestTable::recordHash,
            IdentityRequestTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String identityRequestId(JsonNode row) {
        return CanonicalIds.identityRequestId(text(row, "source_id"));
    }

    /** Part 1 {@code NativeIdentityRequestRepository.recordHash}: every business column, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, BUSINESS);
    }

    private static String ref(JsonNode row) {
        return "request " + text(row, "name") + " (source_id=" + text(row, "source_id") + ")";
    }
}

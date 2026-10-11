package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.integer;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/identity-request-approvals} → {@code kf_identity_request_approval}: the approval summaries of an
 * IdentityRequest ({@code WorkflowSummary$ApprovalSummary}), taken from Part 1's iiq_native.sql +
 * {@code NativeIdentityRequestApprovalRepository} / {@code NativeIdentityRequestParser.toApproval}:
 * <ul>
 *   <li>An approval summary has NO IIQ id of its own. PK {@code identityrequestapprovalid uuid} =
 *       {@link CanonicalIds#identityRequestApprovalId}: name-based over
 *       {@code request_source_id|work_item_id|owner|approval_index}. It is NOT the parent request's id.</li>
 *   <li>Links: the parent REQUEST via {@code request_source_id} (raw IIQ IdentityRequest id; Part 1 back-fills it from
 *       the parent before the response is built) and {@code request_name}; the approval WORK ITEM via
 *       {@code work_item_id} (raw IIQ WorkItem id). There is NO link to an individual request item. All links are text
 *       with no foreign key, as in iiq_native.</li>
 *   <li>17 business columns = the 17 response fields, same names, table order: {@code comments} and {@code sign_off}
 *       are {@code jsonb}; {@code approval_item_count} / {@code approval_index} are integer; {@code approved} boolean;
 *       2 timestamps.</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 17 business fields (Part 1's {@code recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.WorkflowSummary$ApprovalSummary}
 *       (the read surface Part 1 names: the request's approval summaries, not WorkItem objects).</li>
 * </ul>
 */
public final class IdentityRequestApprovalTable {

    private IdentityRequestApprovalTable() {
    }

    public static final String RESOURCE = "identity-request-approvals";
    public static final String TABLE = "kf_identity_request_approval";
    public static final String PK = "identityrequestapprovalid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.WorkflowSummary$ApprovalSummary";

    /** Business columns in iiq_native.kf_identity_request_approval order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("request_source_id"),
            ColumnSpec.text("request_name"),
            ColumnSpec.text("work_item_id"),
            ColumnSpec.text("work_item_type"),
            ColumnSpec.text("owner"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("completer"),
            ColumnSpec.bool("approved"),
            ColumnSpec.text("state"),
            ColumnSpec.text("state_key"),
            ColumnSpec.text("type_key"),
            ColumnSpec.timestamptz("start_date"),
            ColumnSpec.timestamptz("end_date"),
            ColumnSpec.integer("approval_item_count"),
            ColumnSpec.integer("approval_index"),
            ColumnSpec.jsonb("comments"),
            ColumnSpec.jsonb("sign_off"));

    public static final EntitySpec SPEC = new EntitySpec(
            "IdentityRequestApproval", RESOURCE, TABLE, PK, BUSINESS,
            IdentityRequestApprovalTable::identityRequestApprovalId,
            IdentityRequestApprovalTable::recordHash,
            IdentityRequestApprovalTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    /** approval_index is read as Part 1's parser reads it ({@code Integer.valueOf(asInt())}, null stays null). */
    public static String identityRequestApprovalId(JsonNode row) {
        return CanonicalIds.identityRequestApprovalId(text(row, "request_source_id"), text(row, "work_item_id"),
                text(row, "owner"), integer(row, "approval_index"));
    }

    /** Part 1 {@code NativeIdentityRequestApprovalRepository.recordHash}: every business column, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, BUSINESS);
    }

    private static String ref(JsonNode row) {
        return "approval #" + text(row, "approval_index") + " of request " + text(row, "request_name")
                + " (work item " + text(row, "work_item_id") + ", owner " + text(row, "owner") + ")";
    }
}

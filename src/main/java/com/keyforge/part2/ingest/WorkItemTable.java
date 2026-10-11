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
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/work-items} → {@code kf_workitem} (live/open IIQ {@code WorkItem}s), taken from Part 1's
 * iiq_native.sql + {@code NativeWorkItemRepository} / {@code NativeWorkItemMapper}:
 * <ul>
 *   <li>PK {@code workitemid uuid} = {@link CanonicalIds#workItemId}: canonical source_id with NO fallback (a work item
 *       without a usable IIQ id fails its row, as Part 1 refuses it).</li>
 *   <li>Relationships are raw text, no foreign keys, and differ by work-item type: {@code identity_request_id}
 *       (IIQ's {@code getIdentityRequestId()}, verbatim) for request approvals; {@code certification_id} /
 *       {@code certification_entity_id} / {@code certification_item_id} for certification work items;
 *       {@code workflow_case_id} for workflow-driven items; {@code target_id} / {@code target_name} for the object
 *       acted on. Approvals ({@code kf_identity_request_approval.work_item_id}) and provisioning transactions
 *       ({@code manual_work_item_id} / {@code wait_work_item_id}) reference work items by RAW {@code source_id}; once
 *       completed, IIQ moves a work item to WorkItemArchive (the separate workitem-archives entity), so those links
 *       usually resolve there rather than here.</li>
 *   <li>41 business columns = the 41 response fields, same names, table order: 4 {@code jsonb} (comments, sign_offs,
 *       owner_history, approval_set_items); 4 integers; 3 booleans; 6 timestamps.</li>
 *   <li>{@code record_hash} = Part 1's 30-field subset; it EXCLUDES requester_name, assignee_name, handler,
 *       notification_name, certification_entity_id, certification_item_id, entity_type, workflow_case_name,
 *       notification, wake_up_date and modified_at.</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.WorkItem}</li>
 * </ul>
 */
public final class WorkItemTable {

    private WorkItemTable() {
    }

    public static final String RESOURCE = "work-items";
    public static final String TABLE = "kf_workitem";
    public static final String PK = "workitemid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.WorkItem";

    /** Business columns in iiq_native.kf_workitem order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("type"),
            ColumnSpec.text("state"),
            ColumnSpec.text("level"),
            ColumnSpec.text("requester_id"),
            ColumnSpec.text("requester_name"),
            ColumnSpec.text("assignee_id"),
            ColumnSpec.text("assignee_name"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.text("completer"),
            ColumnSpec.text("completion_comments"),
            ColumnSpec.text("handler"),
            ColumnSpec.text("notification_name"),
            ColumnSpec.text("identity_request_id"),
            ColumnSpec.text("target_id"),
            ColumnSpec.text("target_name"),
            ColumnSpec.text("certification_id"),
            ColumnSpec.text("certification_entity_id"),
            ColumnSpec.text("certification_item_id"),
            ColumnSpec.text("entity_type"),
            ColumnSpec.bool("certification_related"),
            ColumnSpec.text("workflow_case_id"),
            ColumnSpec.text("workflow_case_name"),
            ColumnSpec.timestamptz("expiration"),
            ColumnSpec.timestamptz("expiration_date"),
            ColumnSpec.timestamptz("notification"),
            ColumnSpec.timestamptz("wake_up_date"),
            ColumnSpec.integer("escalation_count"),
            ColumnSpec.integer("reminders"),
            ColumnSpec.integer("reminders_sent"),
            ColumnSpec.bool("expired"),
            ColumnSpec.bool("expirable"),
            ColumnSpec.integer("approval_set_item_count"),
            ColumnSpec.jsonb("comments"),
            ColumnSpec.jsonb("sign_offs"),
            ColumnSpec.jsonb("owner_history"),
            ColumnSpec.jsonb("approval_set_items"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "WorkItem", RESOURCE, TABLE, PK, BUSINESS,
            WorkItemTable::workItemId,
            WorkItemTable::recordHash,
            WorkItemTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String workItemId(JsonNode row) {
        return CanonicalIds.workItemId(text(row, "source_id"));
    }

    /** Part 1 {@code NativeWorkItemRepository.recordHash}: 30 fields (see class doc for the 11 it leaves out). */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source_id", text(r, "source_id"));
        b.put("name", text(r, "name"));
        b.put("type", text(r, "type"));
        b.put("state", text(r, "state"));
        b.put("level", text(r, "level"));
        b.put("requester_id", text(r, "requester_id"));
        b.put("assignee_id", text(r, "assignee_id"));
        b.put("owner_id", text(r, "owner_id"));
        b.put("owner_name", text(r, "owner_name"));
        b.put("completer", text(r, "completer"));
        b.put("completion_comments", text(r, "completion_comments"));
        b.put("identity_request_id", text(r, "identity_request_id"));
        b.put("target_id", text(r, "target_id"));
        b.put("target_name", text(r, "target_name"));
        b.put("certification_id", text(r, "certification_id"));
        b.put("certification_related", bool(r, "certification_related"));
        b.put("workflow_case_id", text(r, "workflow_case_id"));
        b.put("expiration", instant(r, "expiration"));
        b.put("expiration_date", instant(r, "expiration_date"));
        b.put("escalation_count", integer(r, "escalation_count"));
        b.put("reminders", integer(r, "reminders"));
        b.put("reminders_sent", integer(r, "reminders_sent"));
        b.put("expired", bool(r, "expired"));
        b.put("expirable", bool(r, "expirable"));
        b.put("approval_set_item_count", integer(r, "approval_set_item_count"));
        b.put("comments", json(r, "comments"));
        b.put("sign_offs", json(r, "sign_offs"));
        b.put("owner_history", json(r, "owner_history"));
        b.put("approval_set_items", json(r, "approval_set_items"));
        b.put("created_at", instant(r, "created_at"));
        return RecordHash.of(b);
    }

    private static String ref(JsonNode row) {
        return "work item " + text(row, "name") + " (" + text(row, "type") + ", source_id=" + text(row, "source_id") + ")";
    }
}

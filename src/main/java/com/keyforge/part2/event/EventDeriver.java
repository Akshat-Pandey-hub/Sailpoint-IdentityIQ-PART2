package com.keyforge.part2.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, DB-free derivation of {@code kf_event} rows from already-loaded native source rows — the event half of Part 1's
 * {@code iiq.nativeevent.NativeEventDeriver}, with its rules, fingerprint parts, {@code src_object_type} values,
 * timestamps and detail JSON unchanged. (Part 1 also emits explicit-id {@code kf_event_link} rows in the same pass;
 * that is Step 5 and is intentionally not done here.) Every event carries {@code src_interface = native_iiq_java_api}
 * and the exact source record id.
 *
 * <ul>
 *   <li>{@code kf_identity_request_approval} → APPROVAL_DECISION (end_date present) / APPROVAL_OPEN;
 *       ts = end_date / start_date</li>
 *   <li>{@code kf_task_result} → TASK_COMPLETED (completed_at present) / TASK_LAUNCHED; ts = completed_at /
 *       launched_at</li>
 *   <li>{@code kf_provisioning_txn} → PROVISIONING_TXN; ts = created_at</li>
 *   <li>{@code kf_workitem_archive} → WORKITEM_ARCHIVED; ts = archived_ts</li>
 *   <li>{@code kf_certification_item} → CERTIFICATION_DECISION, only for a decided item (acted_upon, or an
 *       action_status); ts = action_decision_date, else finished_date, else completed</li>
 * </ul>
 * AuditEvent is intentionally not a source (as in Part 1's native derivation).
 */
public final class EventDeriver {

    public static final String SRC_INTERFACE = "native_iiq_java_api";

    public static final String TYPE_APPROVAL = "sailpoint.object.IdentityRequest.Approval";
    public static final String TYPE_TASK_RESULT = "sailpoint.object.TaskResult";
    public static final String TYPE_PROVISIONING_TXN = "sailpoint.object.ProvisioningTransaction";
    public static final String TYPE_WORKITEM_ARCHIVE = "sailpoint.object.WorkItemArchive";
    public static final String TYPE_CERTIFICATION_ITEM = "sailpoint.object.CertificationItem";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventDeriver() {
    }

    // --- source snapshots: exactly the native columns used (Part 1's records) ---

    /** {@code kf_identity_request_approval}. */
    public record ApprovalSrc(String approvalId, String requestSourceId, String ownerId, String owner, String state,
                              Boolean approved, String completer, String workItemId, Instant startDate,
                              Instant endDate) { }

    /** {@code kf_task_result}. */
    public record TaskSrc(String taskResultId, String name, String type, String completionStatus, String targetClass,
                          String targetId, Instant launched, Instant completed) { }

    /** {@code kf_provisioning_txn}. */
    public record ProvSrc(String txnId, String operation, String type, String status, String planResultStatus,
                          String identityName, String applicationName, String accessRequestId, String ownerId,
                          String waitWorkItemId, String manualWorkItemId, Instant createdAt) { }

    /** {@code kf_workitem_archive}. */
    public record ArchiveSrc(String archiveSourceId, String name, String type, String state, String completer,
                             Boolean signed, String identityRequestId, String workItemId, Instant archivedTs) { }

    /** {@code kf_certification_item}. */
    public record CertItemSrc(String certItemId, String certificationId, String entityId, String identity,
                              String actionStatus, String remediationAction, String actorName, Boolean isApproved,
                              Boolean isRemediation, Boolean isRevokeAccount, Boolean actedUpon, String ownerId,
                              Instant decisionDate, Instant finishedDate, Instant completed) { }

    public static List<EventRow> deriveAll(List<ApprovalSrc> approvals, List<TaskSrc> tasks, List<ProvSrc> provisioning,
                                           List<ArchiveSrc> archives, List<CertItemSrc> certItems, String runId) {
        List<EventRow> out = new ArrayList<>();
        approvals.forEach(s -> out.add(approval(s, runId)));
        tasks.forEach(s -> out.add(task(s, runId)));
        provisioning.forEach(s -> out.add(provisioning(s, runId)));
        archives.forEach(s -> out.add(archive(s, runId)));
        for (CertItemSrc s : certItems) {
            EventRow e = certItem(s, runId);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    static EventRow approval(ApprovalSrc s, String runId) {
        boolean decided = s.endDate() != null;
        String type = decided ? EventType.APPROVAL_DECISION : EventType.APPROVAL_OPEN;
        Instant ts = decided ? s.endDate() : s.startDate();
        String fp = EventFingerprint.fingerprint(type, s.requestSourceId(), s.owner(), s.state(),
                String.valueOf(s.approved()), s.completer(), s.workItemId());
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("requestSourceId", s.requestSourceId());
        detail.put("owner", s.owner());
        detail.put("state", s.state());
        detail.put("approved", s.approved());
        detail.put("completer", s.completer());
        detail.put("workItemId", s.workItemId());
        return event(TYPE_APPROVAL, s.approvalId(), type, fp, ts, runId, detail);
    }

    static EventRow task(TaskSrc s, String runId) {
        boolean completed = s.completed() != null;
        String type = completed ? EventType.TASK_COMPLETED : EventType.TASK_LAUNCHED;
        Instant ts = completed ? s.completed() : s.launched();
        String fp = EventFingerprint.fingerprint(type, s.name(), s.type(), s.completionStatus());
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("name", s.name());
        detail.put("type", s.type());
        detail.put("completionStatus", s.completionStatus());
        return event(TYPE_TASK_RESULT, s.taskResultId(), type, fp, ts, runId, detail);
    }

    static EventRow provisioning(ProvSrc s, String runId) {
        String type = EventType.PROVISIONING_TXN;
        String fp = EventFingerprint.fingerprint(type, s.operation(), s.type(), s.status(), s.planResultStatus(),
                s.applicationName());
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("operation", s.operation());
        detail.put("type", s.type());
        detail.put("status", s.status());
        detail.put("planResultStatus", s.planResultStatus());
        detail.put("identityName", s.identityName());
        detail.put("applicationName", s.applicationName());
        return event(TYPE_PROVISIONING_TXN, s.txnId(), type, fp, s.createdAt(), runId, detail);
    }

    static EventRow archive(ArchiveSrc s, String runId) {
        String type = EventType.WORKITEM_ARCHIVED;
        String fp = EventFingerprint.fingerprint(type, s.name(), s.type(), s.state(), s.completer(),
                String.valueOf(s.signed()));
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("name", s.name());
        detail.put("type", s.type());
        detail.put("state", s.state());
        detail.put("completer", s.completer());
        detail.put("signed", s.signed());
        return event(TYPE_WORKITEM_ARCHIVE, s.archiveSourceId(), type, fp, s.archivedTs(), runId, detail);
    }

    /** One CERTIFICATION_DECISION per decided item; an undecided item yields {@code null} (no fabricated decision). */
    static EventRow certItem(CertItemSrc s, String runId) {
        boolean decided = Boolean.TRUE.equals(s.actedUpon()) || present(s.actionStatus());
        if (!decided) {
            return null;
        }
        Instant ts = s.decisionDate() != null ? s.decisionDate()
                : (s.finishedDate() != null ? s.finishedDate() : s.completed());
        String fp = EventFingerprint.fingerprint(EventType.CERTIFICATION_DECISION, s.certificationId(),
                s.actionStatus(), s.remediationAction(), String.valueOf(s.isApproved()),
                String.valueOf(s.isRemediation()), String.valueOf(s.isRevokeAccount()), s.identity());
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("certificationId", s.certificationId());
        detail.put("entityId", s.entityId());
        detail.put("identity", s.identity());
        detail.put("actionStatus", s.actionStatus());
        detail.put("remediationAction", s.remediationAction());
        detail.put("actorName", s.actorName());
        detail.put("isApproved", s.isApproved());
        detail.put("isRemediation", s.isRemediation());
        detail.put("isRevokeAccount", s.isRevokeAccount());
        return event(TYPE_CERTIFICATION_ITEM, s.certItemId(), EventType.CERTIFICATION_DECISION, fp, ts, runId, detail);
    }

    private static EventRow event(String srcObjectType, String srcObjectId, String type, String fp, Instant ts,
                                  String runId, ObjectNode detail) {
        String eventId = EventFingerprint.eventId(EventFingerprint.SRC_SYSTEM, srcObjectType, srcObjectId, fp);
        return new EventRow(eventId, EventFingerprint.SRC_SYSTEM, srcObjectType, srcObjectId, type, fp, ts,
                ts == null ? null : EventRow.PRECISE, runId, SRC_INTERFACE, detail.toString());
    }

    private static boolean present(String s) {
        return s != null && !s.trim().isEmpty();
    }
}

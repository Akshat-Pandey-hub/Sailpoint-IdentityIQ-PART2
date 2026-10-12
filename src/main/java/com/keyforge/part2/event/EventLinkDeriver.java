package com.keyforge.part2.event;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure, DB-free derivation of the native {@code kf_event_link} rows: the link half of Part 1's
 * {@code iiq.nativeevent.NativeEventDeriver}, with its 13 link types, target types, target columns and link-id formula
 * unchanged. Each link belongs to the event that {@link EventDeriver} derives from the same source row, so link
 * {@code event_id}s are exactly the verified {@code kf_event} ids (the event identity is reused, never recomputed).
 * A link is emitted only for a present, non-blank explicit id; names are never turned into links.
 *
 * <ul>
 *   <li>approval: APPROVAL_OF_REQUEST → IdentityRequest(request_source_id), APPROVAL_OWNER → Identity(owner_id),
 *       APPROVAL_WORKITEM → WorkItem(work_item_id)</li>
 *   <li>task result: TASK_TARGET → simple name of target_class (target_id), only when both are present</li>
 *   <li>provisioning txn: PROVISION_OF_REQUEST → IdentityRequest(access_request_id), PROVISION_OWNER →
 *       Identity(owner_id), PROVISION_WAIT_WORKITEM → WorkItem(wait_work_item_id), PROVISION_MANUAL_WORKITEM →
 *       WorkItem(manual_work_item_id)</li>
 *   <li>work-item archive: WORKITEM_OF_REQUEST → IdentityRequest(identity_request_id), ARCHIVE_OF_WORKITEM →
 *       WorkItem(work_item_id)</li>
 *   <li>certification item (decided items only, i.e. only when its event exists): CERT_OF_CERTIFICATION →
 *       Certification(certification_id), CERT_ITEM_ENTITY → CertificationEntity(entity_id), CERT_OWNER →
 *       Identity(owner_id)</li>
 * </ul>
 */
public final class EventLinkDeriver {

    public static final String APPROVAL_OF_REQUEST = "APPROVAL_OF_REQUEST";
    public static final String APPROVAL_OWNER = "APPROVAL_OWNER";
    public static final String APPROVAL_WORKITEM = "APPROVAL_WORKITEM";
    public static final String TASK_TARGET = "TASK_TARGET";
    public static final String PROVISION_OF_REQUEST = "PROVISION_OF_REQUEST";
    public static final String PROVISION_OWNER = "PROVISION_OWNER";
    public static final String PROVISION_WAIT_WORKITEM = "PROVISION_WAIT_WORKITEM";
    public static final String PROVISION_MANUAL_WORKITEM = "PROVISION_MANUAL_WORKITEM";
    public static final String WORKITEM_OF_REQUEST = "WORKITEM_OF_REQUEST";
    public static final String ARCHIVE_OF_WORKITEM = "ARCHIVE_OF_WORKITEM";
    public static final String CERT_OF_CERTIFICATION = "CERT_OF_CERTIFICATION";
    public static final String CERT_ITEM_ENTITY = "CERT_ITEM_ENTITY";
    public static final String CERT_OWNER = "CERT_OWNER";

    private EventLinkDeriver() {
    }

    public static List<EventLinkRow> deriveAll(List<EventDeriver.ApprovalSrc> approvals,
                                               List<EventDeriver.TaskSrc> tasks,
                                               List<EventDeriver.ProvSrc> provisioning,
                                               List<EventDeriver.ArchiveSrc> archives,
                                               List<EventDeriver.CertItemSrc> certItems, String runId) {
        List<EventLinkRow> out = new ArrayList<>();
        approvals.forEach(s -> approval(s, runId, out));
        tasks.forEach(s -> task(s, runId, out));
        provisioning.forEach(s -> provisioning(s, runId, out));
        archives.forEach(s -> archive(s, runId, out));
        certItems.forEach(s -> certItem(s, runId, out));
        return out;
    }

    static void approval(EventDeriver.ApprovalSrc s, String runId, List<EventLinkRow> out) {
        EventRow e = EventDeriver.approval(s, runId);
        link(out, e, "IdentityRequest", s.requestSourceId(), APPROVAL_OF_REQUEST, runId);
        link(out, e, "Identity", s.ownerId(), APPROVAL_OWNER, runId);
        link(out, e, "WorkItem", s.workItemId(), APPROVAL_WORKITEM, runId);
    }

    static void task(EventDeriver.TaskSrc s, String runId, List<EventLinkRow> out) {
        EventRow e = EventDeriver.task(s, runId);
        // Only an explicit target id yields a link; a target name alone never does.
        if (present(s.targetId()) && present(s.targetClass())) {
            link(out, e, simpleType(s.targetClass()), s.targetId(), TASK_TARGET, runId);
        }
    }

    static void provisioning(EventDeriver.ProvSrc s, String runId, List<EventLinkRow> out) {
        EventRow e = EventDeriver.provisioning(s, runId);
        link(out, e, "IdentityRequest", s.accessRequestId(), PROVISION_OF_REQUEST, runId);
        link(out, e, "Identity", s.ownerId(), PROVISION_OWNER, runId);
        link(out, e, "WorkItem", s.waitWorkItemId(), PROVISION_WAIT_WORKITEM, runId);
        link(out, e, "WorkItem", s.manualWorkItemId(), PROVISION_MANUAL_WORKITEM, runId);
    }

    static void archive(EventDeriver.ArchiveSrc s, String runId, List<EventLinkRow> out) {
        EventRow e = EventDeriver.archive(s, runId);
        link(out, e, "IdentityRequest", s.identityRequestId(), WORKITEM_OF_REQUEST, runId);
        link(out, e, "WorkItem", s.workItemId(), ARCHIVE_OF_WORKITEM, runId);
    }

    /** An undecided item has no event, hence no links (as in Part 1). The identity NAME never becomes a link. */
    static void certItem(EventDeriver.CertItemSrc s, String runId, List<EventLinkRow> out) {
        EventRow e = EventDeriver.certItem(s, runId);
        if (e == null) {
            return;
        }
        link(out, e, "Certification", s.certificationId(), CERT_OF_CERTIFICATION, runId);
        link(out, e, "CertificationEntity", s.entityId(), CERT_ITEM_ENTITY, runId);
        link(out, e, "Identity", s.ownerId(), CERT_OWNER, runId);
    }

    /** Part 1's link id: {@code UUID.nameUUIDFromBytes(eventId|linkType|targetType|trimmed targetId)}. */
    public static String linkId(String eventId, String linkType, String targetType, String targetId) {
        String key = eventId + "|" + linkType + "|" + targetType + "|" + targetId.trim();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Adds one explicit link, only when {@code targetId} is a real, non-blank id. */
    private static void link(List<EventLinkRow> out, EventRow e, String targetType, String targetId,
                             String linkType, String runId) {
        if (!present(targetId)) {
            return;
        }
        out.add(new EventLinkRow(linkId(e.eventId(), linkType, targetType, targetId), e.eventId(),
                e.srcObjectType(), e.srcObjectId(), targetType, targetId.trim(), linkType, EventLinkRow.EXPLICIT,
                runId));
    }

    private static boolean present(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Last dotted segment of a class name (e.g. {@code sailpoint.object.Identity} → {@code Identity}). */
    static String simpleType(String className) {
        if (className == null) {
            return null;
        }
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }
}

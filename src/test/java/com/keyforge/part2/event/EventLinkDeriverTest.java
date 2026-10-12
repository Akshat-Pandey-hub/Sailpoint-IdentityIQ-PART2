package com.keyforge.part2.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventLinkDeriver}: Part 1's 13 native link rules, plus golden values — real {@code kf_event_link} rows of the
 * reference dump rebuilt from their source ids, asserting the same event_id and link_id byte for byte.
 */
class EventLinkDeriverTest {

    private static final String RUN = "run-1";
    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

    private static Map<String, EventLinkRow> byType(List<EventLinkRow> links) {
        return links.stream().collect(Collectors.toMap(EventLinkRow::linkType, l -> l));
    }

    private static void assertLink(EventLinkRow l, EventRow event, String targetType, String targetId) {
        assertEquals(event.eventId(), l.eventId(), "belongs to the derived (stored) event");
        assertEquals(event.srcObjectType(), l.srcObjectType());
        assertEquals(event.srcObjectId(), l.srcObjectId());
        assertEquals(targetType, l.targetObjectType());
        assertEquals(targetId, l.targetObjectId());
        assertEquals("EXPLICIT", l.linkStatus());
        assertEquals(RUN, l.extractionRunId());
        assertEquals(EventLinkDeriver.linkId(event.eventId(), l.linkType(), targetType, targetId), l.linkId());
    }

    // ---- the 13 link types ----

    @Test
    void approvalLinks() {
        EventDeriver.ApprovalSrc s = new EventDeriver.ApprovalSrc("ap1", "req1", "own1", "spadmin", "Finished", true,
                "x", "wi1", T, T);
        List<EventLinkRow> out = new ArrayList<>();
        EventLinkDeriver.approval(s, RUN, out);
        EventRow e = EventDeriver.approval(s, RUN);
        Map<String, EventLinkRow> m = byType(out);
        assertEquals(3, out.size());
        assertLink(m.get("APPROVAL_OF_REQUEST"), e, "IdentityRequest", "req1");
        assertLink(m.get("APPROVAL_OWNER"), e, "Identity", "own1");
        assertLink(m.get("APPROVAL_WORKITEM"), e, "WorkItem", "wi1");
    }

    @Test
    void taskTargetUsesTheSimpleClassNameAndNeedsBothColumns() {
        EventDeriver.TaskSrc s = new EventDeriver.TaskSrc("t1", "Refresh", "System", "Success",
                "sailpoint.object.ManagedAttribute", "ma1", T, T);
        List<EventLinkRow> out = new ArrayList<>();
        EventLinkDeriver.task(s, RUN, out);
        assertEquals(1, out.size());
        assertEquals("TASK_TARGET", out.get(0).linkType());
        assertLink(out.get(0), EventDeriver.task(s, RUN), "ManagedAttribute", "ma1");

        List<EventLinkRow> none = new ArrayList<>();
        EventLinkDeriver.task(new EventDeriver.TaskSrc("t2", "n", "t", null, null, "id1", T, null), RUN, none);
        EventLinkDeriver.task(new EventDeriver.TaskSrc("t3", "n", "t", null, "sailpoint.object.Identity", " ", T,
                null), RUN, none);
        assertTrue(none.isEmpty(), "no class or blank id → no link (a name alone never links)");
        assertEquals("Identity", EventLinkDeriver.simpleType("Identity"), "undotted class kept as is");
    }

    @Test
    void provisioningLinks() {
        EventDeriver.ProvSrc s = new EventDeriver.ProvSrc("px1", "Create", "Auto", "Success", null, "alice", "LDAP",
                "req1", "own1", "wait1", "man1", T);
        List<EventLinkRow> out = new ArrayList<>();
        EventLinkDeriver.provisioning(s, RUN, out);
        EventRow e = EventDeriver.provisioning(s, RUN);
        Map<String, EventLinkRow> m = byType(out);
        assertEquals(4, out.size());
        assertLink(m.get("PROVISION_OF_REQUEST"), e, "IdentityRequest", "req1");
        assertLink(m.get("PROVISION_OWNER"), e, "Identity", "own1");
        assertLink(m.get("PROVISION_WAIT_WORKITEM"), e, "WorkItem", "wait1");
        assertLink(m.get("PROVISION_MANUAL_WORKITEM"), e, "WorkItem", "man1");
    }

    @Test
    void archiveLinks() {
        EventDeriver.ArchiveSrc s = new EventDeriver.ArchiveSrc("arc1", "Approve", "Approval", "Finished", null,
                true, "req1", "wi1", T);
        List<EventLinkRow> out = new ArrayList<>();
        EventLinkDeriver.archive(s, RUN, out);
        EventRow e = EventDeriver.archive(s, RUN);
        Map<String, EventLinkRow> m = byType(out);
        assertEquals(2, out.size());
        assertLink(m.get("WORKITEM_OF_REQUEST"), e, "IdentityRequest", "req1");
        assertLink(m.get("ARCHIVE_OF_WORKITEM"), e, "WorkItem", "wi1");
    }

    @Test
    void certificationLinksOnlyForDecidedItems() {
        EventDeriver.CertItemSrc decided = new EventDeriver.CertItemSrc("ci1", "cert1", "ent1", "Alice", "Approved",
                null, "spadmin", true, false, false, true, "own1", T, null, null);
        List<EventLinkRow> out = new ArrayList<>();
        EventLinkDeriver.certItem(decided, RUN, out);
        EventRow e = EventDeriver.certItem(decided, RUN);
        Map<String, EventLinkRow> m = byType(out);
        assertEquals(3, out.size());
        assertLink(m.get("CERT_OF_CERTIFICATION"), e, "Certification", "cert1");
        assertLink(m.get("CERT_ITEM_ENTITY"), e, "CertificationEntity", "ent1");
        assertLink(m.get("CERT_OWNER"), e, "Identity", "own1");
        assertTrue(out.stream().noneMatch(l -> l.targetObjectId().equals("Alice")), "identity NAME is never a link");

        List<EventLinkRow> undecided = new ArrayList<>();
        EventLinkDeriver.certItem(new EventDeriver.CertItemSrc("ci2", "cert1", "ent1", "Bob", null, null, null,
                null, null, null, false, "own1", null, null, null), RUN, undecided);
        assertTrue(undecided.isEmpty(), "no event → no links");
    }

    @Test
    void allThirteenTypesAreProduced() {
        List<EventLinkRow> all = EventLinkDeriver.deriveAll(
                List.of(new EventDeriver.ApprovalSrc("ap1", "r", "o", null, null, null, null, "w", T, null)),
                List.of(new EventDeriver.TaskSrc("t1", null, null, null, "sailpoint.object.Identity", "i", T, null)),
                List.of(new EventDeriver.ProvSrc("p1", null, null, null, null, null, null, "r", "o", "w1", "w2", T)),
                List.of(new EventDeriver.ArchiveSrc("a1", null, null, null, null, null, "r", "w", T)),
                List.of(new EventDeriver.CertItemSrc("c1", "c", "e", null, "Approved", null, null, null, null, null,
                        null, "o", T, null, null)),
                RUN);
        assertEquals(List.of("APPROVAL_OF_REQUEST", "APPROVAL_OWNER", "APPROVAL_WORKITEM", "TASK_TARGET",
                "PROVISION_OF_REQUEST", "PROVISION_OWNER", "PROVISION_WAIT_WORKITEM", "PROVISION_MANUAL_WORKITEM",
                "WORKITEM_OF_REQUEST", "ARCHIVE_OF_WORKITEM", "CERT_OF_CERTIFICATION", "CERT_ITEM_ENTITY",
                "CERT_OWNER"), all.stream().map(EventLinkRow::linkType).toList());
    }

    // ---- null / blank / trimming / identity ----

    @Test
    void nullOrBlankTargetsYieldNoLinkAndIdsAreTrimmed() {
        List<EventLinkRow> out = new ArrayList<>();
        EventLinkDeriver.provisioning(new EventDeriver.ProvSrc("px1", null, null, null, null, null, null,
                null, "", "  ", " wi9 ", T), RUN, out);
        assertEquals(1, out.size());
        assertEquals("PROVISION_MANUAL_WORKITEM", out.get(0).linkType());
        assertEquals("wi9", out.get(0).targetObjectId(), "verbatim id, trimmed (Part 1)");
        assertEquals(EventLinkDeriver.linkId(out.get(0).eventId(), "PROVISION_MANUAL_WORKITEM", "WorkItem", "wi9"),
                out.get(0).linkId());
    }

    @Test
    void linkIdDependsOnEveryComponentButNotTheRun() {
        String id = EventLinkDeriver.linkId("e", "T", "WorkItem", "w");
        assertNotEquals(id, EventLinkDeriver.linkId("e2", "T", "WorkItem", "w"));
        assertNotEquals(id, EventLinkDeriver.linkId("e", "T2", "WorkItem", "w"));
        assertNotEquals(id, EventLinkDeriver.linkId("e", "T", "Identity", "w"));
        assertNotEquals(id, EventLinkDeriver.linkId("e", "T", "WorkItem", "w2"));
        assertEquals(id, EventLinkDeriver.linkId("e", "T", "WorkItem", " w "));
        EventDeriver.ArchiveSrc s = new EventDeriver.ArchiveSrc("a1", null, null, null, null, null, "r", "w", T);
        List<EventLinkRow> run1 = new ArrayList<>();
        List<EventLinkRow> run2 = new ArrayList<>();
        EventLinkDeriver.archive(s, "run-1", run1);
        EventLinkDeriver.archive(s, "run-2", run2);
        assertEquals(run1.get(0).linkId(), run2.get(0).linkId());
    }

    // ---- golden rows from the reference dump (iiq_native.kf_event_link) ----

    @Test
    void goldenApprovalLinks() {
        EventDeriver.ApprovalSrc s = new EventDeriver.ApprovalSrc("81c644c6-d829-3bad-a19d-72489d052665",
                "7f0001019f061fdc819f06e6cb7f002f", null, "spadmin", "Finished", false, "The Administrator",
                "7f0001019f061fdc819f06e6cbf30030", null, Instant.parse("2026-06-27T02:30:24.753Z"));
        List<EventLinkRow> out = new ArrayList<>();
        EventLinkDeriver.approval(s, RUN, out);
        Map<String, EventLinkRow> m = byType(out);
        assertEquals(2, out.size(), "owner_id absent in this row → no APPROVAL_OWNER (as in the dump)");
        assertEquals("2e0cb64d-db3e-3ce8-ac5f-a00dc2ff855a", m.get("APPROVAL_OF_REQUEST").eventId());
        assertEquals("b3fa6f27-57c0-31dd-94b4-e0de8b654287", m.get("APPROVAL_OF_REQUEST").linkId());
        assertEquals("05a384b2-c060-381b-9802-65e9a171774a", m.get("APPROVAL_WORKITEM").linkId());
    }

    @Test
    void goldenTaskTargetLink() {
        EventDeriver.TaskSrc s = new EventDeriver.TaskSrc("7f000101-9fbf-1a17-819f-cd8d05321c27",
                "RapidSetup Leaver FOR App_IDJ0001009 1785859989530", "Workflow", null, "sailpoint.object.Identity",
                "7f0001019f061fdc819f9cdef3bc0862", Instant.parse("2026-08-04T16:13:18.947Z"), null);
        List<EventLinkRow> out = new ArrayList<>();
        EventLinkDeriver.task(s, RUN, out);
        assertEquals("59e182f0-fe97-348b-9d91-b85f7a6ddea2", out.get(0).eventId());
        assertEquals("Identity", out.get(0).targetObjectType());
        assertEquals("2433ad41-730d-3b3c-a21c-82929685557c", out.get(0).linkId());
    }

    @Test
    void goldenCertificationLinks() {
        EventDeriver.CertItemSrc s = new EventDeriver.CertItemSrc("7f000101-a08f-1fbf-81a0-a5bc428d27d4",
                "7f000101a08f1fbf81a0a5bc304b27d0", "7f000101a08f1fbf81a0a5bc42b327f2", "Alexander Evans",
                "Approved", null, "spadmin", true, false, false, true, null,
                Instant.parse("2026-10-02T15:23:51.988Z"), null, null);
        List<EventLinkRow> out = new ArrayList<>();
        EventLinkDeriver.certItem(s, RUN, out);
        Map<String, EventLinkRow> m = byType(out);
        assertEquals(2, out.size(), "owner_id absent → no CERT_OWNER (as in the dump)");
        assertEquals("66999d7b-d1e0-370c-97a6-5b6f9586712d", m.get("CERT_OF_CERTIFICATION").eventId());
        assertEquals("34e33fcd-186d-347b-86ec-5ffdff0c2407", m.get("CERT_OF_CERTIFICATION").linkId());
        assertEquals("aaab73c3-ce09-35bd-a08d-e77fdca331af", m.get("CERT_ITEM_ENTITY").linkId());
    }
}

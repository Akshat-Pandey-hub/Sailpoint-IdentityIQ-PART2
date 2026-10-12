package com.keyforge.part2.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EventDeriver}: Part 1's {@code NativeEventDeriverTest} event rules (links are Step 5, not asserted here). */
class EventDeriverTest {

    private static final String RUN = "run-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-01-03T10:00:00Z");

    private static EventDeriver.ApprovalSrc approval(Instant start, Instant end) {
        return new EventDeriver.ApprovalSrc("ap1", "req1", "own1", "spadmin", "Finished", true, "The Administrator",
                "wi1", start, end);
    }

    private static EventDeriver.CertItemSrc certItem(Boolean actedUpon, String actionStatus, Instant decision,
                                                     Instant finished, Instant completed) {
        return new EventDeriver.CertItemSrc("ci1", "cert1", "ent1", "Alice", actionStatus, null, "spadmin", true,
                false, false, actedUpon, "own1", decision, finished, completed);
    }

    @Test
    void approvalDecidedVersusOpen() {
        EventRow decided = EventDeriver.approval(approval(T1, T2), RUN);
        assertEquals(EventType.APPROVAL_DECISION, decided.eventType());
        assertEquals(T2, decided.srcEventTs(), "decision time = end_date");
        EventRow open = EventDeriver.approval(approval(T1, null), RUN);
        assertEquals(EventType.APPROVAL_OPEN, open.eventType());
        assertEquals(T1, open.srcEventTs(), "open time = start_date");
        assertNotEquals(decided.eventId(), open.eventId(), "the decision is a new event, not a mutation");
        assertEquals(EventDeriver.TYPE_APPROVAL, decided.srcObjectType());
        assertEquals("ap1", decided.srcObjectId());
    }

    @Test
    void taskCompletedVersusLaunched() {
        EventRow completed = EventDeriver.task(new EventDeriver.TaskSrc("t1", "Refresh", "System", "Success",
                null, null, T1, T2), RUN);
        assertEquals(EventType.TASK_COMPLETED, completed.eventType());
        assertEquals(T2, completed.srcEventTs());
        EventRow launched = EventDeriver.task(new EventDeriver.TaskSrc("t1", "Refresh", "System", null,
                null, null, T1, null), RUN);
        assertEquals(EventType.TASK_LAUNCHED, launched.eventType());
        assertEquals(T1, launched.srcEventTs());
    }

    @Test
    void provisioningAndArchiveUseTheirOwnTimestampAndIds() {
        EventRow p = EventDeriver.provisioning(new EventDeriver.ProvSrc("px1", "Create", "Auto", "Success", "committed",
                "alice", "LDAP", "req1", "own1", null, null, T3), RUN);
        assertEquals(EventType.PROVISIONING_TXN, p.eventType());
        assertEquals(EventDeriver.TYPE_PROVISIONING_TXN, p.srcObjectType());
        assertEquals("px1", p.srcObjectId());
        assertEquals(T3, p.srcEventTs());

        EventRow a = EventDeriver.archive(new EventDeriver.ArchiveSrc("arc1", "Approve", "Approval", "Finished",
                "spadmin", false, "req1", "wi1", T2), RUN);
        assertEquals(EventType.WORKITEM_ARCHIVED, a.eventType());
        assertEquals(EventDeriver.TYPE_WORKITEM_ARCHIVE, a.srcObjectType());
        assertEquals("arc1", a.srcObjectId(), "the archive's own source_id");
        assertEquals(T2, a.srcEventTs());
    }

    @Test
    void certItemOnlyWhenDecidedAndTimestampFallsBack() {
        assertNull(EventDeriver.certItem(certItem(false, null, T1, T2, T3), RUN), "undecided → no event");
        assertNull(EventDeriver.certItem(certItem(null, "  ", T1, T2, T3), RUN), "blank status is not a decision");
        assertEquals(T1, EventDeriver.certItem(certItem(true, null, T1, T2, T3), RUN).srcEventTs());
        assertEquals(T2, EventDeriver.certItem(certItem(null, "Approved", null, T2, T3), RUN).srcEventTs());
        assertEquals(T3, EventDeriver.certItem(certItem(true, null, null, null, T3), RUN).srcEventTs());
        EventRow e = EventDeriver.certItem(certItem(true, "Approved", T1, null, null), RUN);
        assertEquals(EventType.CERTIFICATION_DECISION, e.eventType());
        assertEquals(EventDeriver.TYPE_CERTIFICATION_ITEM, e.srcObjectType());
    }

    @Test
    void missingTimestampHasNoPrecision() {
        EventRow e = EventDeriver.provisioning(new EventDeriver.ProvSrc("px1", "Create", null, null, null, null, null,
                null, null, null, null, null), RUN);
        assertNull(e.srcEventTs());
        assertNull(e.srcEventTsPrecision(), "no fabricated time");
        assertEquals(EventRow.PRECISE, EventDeriver.approval(approval(T1, T2), RUN).srcEventTsPrecision());
    }

    @Test
    void everyEventCarriesProvenanceAndRun() {
        EventRow e = EventDeriver.approval(approval(T1, T2), RUN);
        assertEquals("IdentityIQ", e.srcSystem());
        assertEquals("native_iiq_java_api", e.srcInterface());
        assertEquals(RUN, e.extractionRunId());
    }

    @Test
    void detailJsonCarriesBusinessAttributesWithNulls() throws Exception {
        JsonNode d = MAPPER.readTree(EventDeriver.task(new EventDeriver.TaskSrc("t1", "Refresh", "System", null,
                "sailpoint.object.Identity", "id1", T1, null), RUN).eventDetailJson());
        assertEquals("Refresh", d.get("name").asText());
        assertTrue(d.has("completionStatus") && d.get("completionStatus").isNull(), "null kept as JSON null");
        assertEquals(3, d.size(), "only Part 1's detail attributes");

        JsonNode c = MAPPER.readTree(EventDeriver.certItem(certItem(true, "Approved", T1, null, null), RUN)
                .eventDetailJson());
        assertEquals(9, c.size());
        assertTrue(c.get("isApproved").asBoolean());
    }

    @Test
    void sameSourceFactYieldsSameIdAndAChangedAttributeANewOne() {
        EventDeriver.ProvSrc s = new EventDeriver.ProvSrc("px1", "Create", "Auto", "Pending", null, "alice", "LDAP",
                null, null, null, null, T1);
        EventDeriver.ProvSrc changed = new EventDeriver.ProvSrc("px1", "Create", "Auto", "Success", null, "alice",
                "LDAP", null, null, null, null, T1);
        assertEquals(EventDeriver.provisioning(s, RUN).eventId(), EventDeriver.provisioning(s, "run-2").eventId(),
                "run id is not part of the identity");
        assertNotEquals(EventDeriver.provisioning(s, RUN).eventId(), EventDeriver.provisioning(changed, RUN).eventId());
    }

    @Test
    void deriveAllCoversEverySourceAndSkipsUndecidedItems() {
        List<EventRow> all = EventDeriver.deriveAll(
                List.of(approval(T1, T2)),
                List.of(new EventDeriver.TaskSrc("t1", "Refresh", "System", "Success", null, null, T1, T2)),
                List.of(new EventDeriver.ProvSrc("px1", "Create", "Auto", "Success", null, null, null, null, null,
                        null, null, T1)),
                List.of(new EventDeriver.ArchiveSrc("arc1", "n", "Approval", "Finished", null, null, null, null, T1)),
                List.of(certItem(true, "Approved", T1, null, null), certItem(false, null, null, null, null)),
                RUN);
        assertEquals(List.of(EventType.APPROVAL_DECISION, EventType.TASK_COMPLETED, EventType.PROVISIONING_TXN,
                        EventType.WORKITEM_ARCHIVED, EventType.CERTIFICATION_DECISION),
                all.stream().map(EventRow::eventType).toList());
    }
}

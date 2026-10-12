package com.keyforge.part2.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventFingerprint} (Part 1 port) plus golden values: each case rebuilds one real {@code kf_event} row of Part
 * 1's native derivation (dump {@code iiq_native.kf_event}) from its detail JSON / source id, and asserts Part 2
 * reproduces the stored {@code event_fingerprint} and {@code event_id} byte for byte.
 */
class EventFingerprintTest {

    private static final String RUN = "run-1";

    @Test
    void fingerprintIsStableTrimmedNullSafeAndCaseSensitive() {
        String a = EventFingerprint.fingerprint("A", "b", null);
        assertEquals(a, EventFingerprint.fingerprint(" A ", "b", null), "trimmed");
        assertEquals(a, EventFingerprint.fingerprint("A", "b", ""), "null == empty");
        assertNotEquals(a, EventFingerprint.fingerprint("a", "b", null), "not lower-cased");
        assertNotEquals(a, EventFingerprint.fingerprint("b", "A", null), "order-preserving");
        assertEquals(EventFingerprint.md5("A|b|"), a);
        assertTrue(a.matches("[0-9a-f]{32}"), a);
    }

    @Test
    void eventIdDependsOnEveryIdentityComponent() {
        String id = EventFingerprint.eventId("IdentityIQ", "T", "1", "fp");
        assertEquals(id, EventFingerprint.eventId("IdentityIQ", "T", "1", "fp"));
        assertNotEquals(id, EventFingerprint.eventId("IdentityIQ", "T", "2", "fp"));
        assertNotEquals(id, EventFingerprint.eventId("IdentityIQ", "U", "1", "fp"));
        assertNotEquals(id, EventFingerprint.eventId("IdentityIQ", "T", "1", "fp2"));
    }

    @Test
    void goldenApprovalDecision() {
        EventRow e = EventDeriver.approval(new EventDeriver.ApprovalSrc("81c644c6-d829-3bad-a19d-72489d052665",
                "7f0001019f061fdc819f06e6cb7f002f", null, "spadmin", "Finished", false, "The Administrator",
                "7f0001019f061fdc819f06e6cbf30030", null, Instant.parse("2026-06-27T02:30:24.753Z")), RUN);
        assertEquals("APPROVAL_DECISION", e.eventType());
        assertEquals("c29504cd4d3e682c4b1955c060f94d2a", e.eventFingerprint());
        assertEquals("2e0cb64d-db3e-3ce8-ac5f-a00dc2ff855a", e.eventId());
    }

    @Test
    void goldenTaskCompletedAndLaunched() {
        EventRow done = EventDeriver.task(new EventDeriver.TaskSrc("7f000101-9842-1229-8198-4a1b272710dc",
                "EntraTargetEnt", "System", "Success", null, null, null,
                Instant.parse("2025-07-27T04:19:10.310Z")), RUN);
        assertEquals("TASK_COMPLETED", done.eventType());
        assertEquals("985a32499dc856fa152f8040080a6ef1", done.eventFingerprint());
        assertEquals("7ae16659-d386-3a55-8dbf-8f7fbaf62109", done.eventId());

        EventRow launched = EventDeriver.task(new EventDeriver.TaskSrc("7f000101-9fbf-1a17-819f-cd8d05321c27",
                "RapidSetup Leaver FOR App_IDJ0001009 1785859989530", "Workflow", null, null, null,
                Instant.parse("2026-08-04T16:13:18.947Z"), null), RUN);
        assertEquals("TASK_LAUNCHED", launched.eventType());
        assertEquals("4af74022ef1611c4964f4b0a057c20f6", launched.eventFingerprint());
        assertEquals("59e182f0-fe97-348b-9d91-b85f7a6ddea2", launched.eventId());
    }

    @Test
    void goldenProvisioningTxn() {
        EventRow e = EventDeriver.provisioning(new EventDeriver.ProvSrc("7f000101-9fbf-1a17-819f-c936d78311b4",
                "Create", "Auto", "Failed", "failed", "App_IDJ0001011", "corp directory-LDAP-Target",
                null, null, null, null, Instant.parse("2026-08-03T20:00:42.371Z")), RUN);
        assertEquals("c7d83528023d817b8ce78482e24d3d72", e.eventFingerprint());
        assertEquals("0bcece8b-e2f0-3431-a0c8-d41ce02c6366", e.eventId());
    }

    @Test
    void goldenCertificationDecision() {
        EventRow e = EventDeriver.certItem(new EventDeriver.CertItemSrc("7f000101-a08f-1fbf-81a0-a5bc428d27d4",
                "7f000101a08f1fbf81a0a5bc304b27d0", "7f000101a08f1fbf81a0a5bc42b327f2", "Alexander Evans",
                "Approved", null, "spadmin", true, false, false, true, null,
                Instant.parse("2026-10-02T15:23:51.988Z"), null, null), RUN);
        assertEquals("08a9218de3f6dc3f8f474c2c4ca16d0d", e.eventFingerprint());
        assertEquals("66999d7b-d1e0-370c-97a6-5b6f9586712d", e.eventId());
    }
}

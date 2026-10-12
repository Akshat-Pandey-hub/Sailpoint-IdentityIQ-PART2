package com.keyforge.part2.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RunLedger}: one {@code kf_extraction_run} row per command run, Part 1 statuses + INCOMPLETE, best-effort. */
class RunLedgerTest {

    private final List<ExtractionRun> recorded = new ArrayList<>();
    private RunLedger.Sink previous;

    @BeforeEach
    void captureSink() {
        previous = RunLedger.useSink(recorded::add);
    }

    @AfterEach
    void restoreSink() {
        RunLedger.useSink(previous);
        RunLedger.discard();
    }

    private static RunContext.EntityCount count(String table, int fetched, int ins, int upd, int failed, int skipped) {
        return new RunContext.EntityCount(table, fetched, ins, upd, failed, skipped, null, null, null);
    }

    @Test
    void oneRowPerRunWhoseIdIsTheExtractionRunIdStampedOnRows() {
        String[] seenInside = new String[1];
        int exit = RunLedger.run("import-identity", () -> {
            seenInside[0] = RunLedger.runIdOrNew();
            RunLedger.record(count("kf_identity", 68, 0, 68, 0, 0));
            return 0;
        });

        assertEquals(0, exit);
        assertEquals(1, recorded.size());
        ExtractionRun r = recorded.get(0);
        assertEquals(seenInside[0], r.extractionRunId(), "ledger row joins the rows the run stamped");
        assertEquals("import-identity", r.command());
        assertEquals("kfagent-rest", r.sourceInterface());
        assertEquals("SUCCESS", r.status());
        assertEquals(68, r.extracted());
        assertEquals(68, r.updated());
        assertEquals(0, r.failed());
        assertNull(r.errorMessage());
        assertNotNull(r.endedAt());
        assertTrue(r.durationMs() >= 0);
        assertNull(RunLedger.currentRunId(), "the run is closed afterwards");
    }

    @Test
    void reconcileCommandsAreRecordedAsDerived() {
        RunLedger.run("reconcile-native-workitems", () -> 0);
        RunLedger.run("import-identity --incremental", () -> 0);
        assertEquals("derived", recorded.get(0).sourceInterface(), "computed from loaded tables (Part 1's value)");
        assertEquals("kfagent-rest", recorded.get(1).sourceInterface());
    }

    @Test
    void repeatRunsGetDistinctIds() {
        RunLedger.run("import-role", () -> 0);
        RunLedger.run("import-role", () -> 0);
        assertEquals(2, recorded.size());
        assertNotEquals(recorded.get(0).extractionRunId(), recorded.get(1).extractionRunId());
    }

    @Test
    void statusesFollowPart1PlusIncomplete() {
        assertEquals("SUCCESS", RunLedger.statusFor(0, 0));
        assertEquals("COMPLETED_WITH_FAILURES", RunLedger.statusFor(0, 2));
        assertEquals("COMPLETED_WITH_FAILURES", RunLedger.statusFor(6, 2));
        assertEquals("INCOMPLETE", RunLedger.statusFor(7, 0));
        for (int code : new int[]{1, 3, 4, 5}) {
            assertEquals("FAILED", RunLedger.statusFor(code, 0));
        }
        assertEquals("configuration error", RunLedger.reasonFor(3));
        assertEquals("KF Agent HTTP/source error", RunLedger.reasonFor(4));
        assertEquals("PostgreSQL error", RunLedger.reasonFor(5));
        assertEquals("scan / sweep completeness not confirmed", RunLedger.reasonFor(7));
    }

    @Test
    void aFailedRunIsRecordedWithItsError() {
        int exit = RunLedger.run("import-account", () -> {
            RunLedger.error("KF Agent HTTP/source error: connection refused");
            return 4;
        });
        assertEquals(4, exit);
        ExtractionRun r = recorded.get(0);
        assertEquals("FAILED", r.status());
        assertEquals("KF Agent HTTP/source error: connection refused", r.errorMessage());
        assertNull(r.extracted(), "no table reported -> no totals");
        assertNull(r.entityCountsJson());

        RunLedger.run("import-account", () -> 5);
        assertEquals("PostgreSQL error (exit code 5)", recorded.get(1).errorMessage(), "default reason");
    }

    @Test
    void aRunnerThatThrowsIsStillRecordedAndTheExceptionPropagates() {
        assertThrows(IllegalStateException.class, () -> RunLedger.run("import-policy", () -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, recorded.size());
        assertEquals("FAILED", recorded.get(0).status());
        assertEquals("unexpected error: boom", recorded.get(0).errorMessage());
    }

    @Test
    void aLedgerWriteFailureNeverChangesTheExitCode() {
        RunLedger.useSink(run -> {
            throw new java.sql.SQLException("PostgreSQL unreachable");
        });
        assertEquals(6, RunLedger.run("import-work-item", () -> 6));
        assertNull(RunLedger.currentRunId());
    }

    @Test
    void entityCountsCarrySkippedAndTheSweepOutcome() throws Exception {
        RunLedger.run("import-identity-entitlement", () -> {
            RunLedger.record(new RunContext.EntityCount("kf_identity_entitlement", 424, 0, 424, 0, 0,
                    "SWEPT", 2, 1));
            return 0;
        });
        RunLedger.run("import-audit-event", () -> {
            RunLedger.record(count("kf_audit_event", 330, 0, 0, 0, 330));
            return 0;
        });
        JsonNode swept = new ObjectMapper().readTree(recorded.get(0).entityCountsJson()).get(0);
        assertEquals("kf_identity_entitlement", swept.get("entity").asText());
        assertEquals("SWEPT", swept.get("sweep").asText());
        assertEquals(2, swept.get("marked_deleted").asInt());
        assertEquals(1, swept.get("revived").asInt());

        JsonNode appended = new ObjectMapper().readTree(recorded.get(1).entityCountsJson()).get(0);
        assertEquals(330, appended.get("skipped").asInt(), "append-only re-run: existing rows skipped");
        assertTrue(appended.get("sweep") == null, "no sweep fields for a table that is not swept");
    }

    @Test
    void incompleteRunsAreRecordedAsIncomplete() {
        RunLedger.run("import-syslog-event", () -> {
            RunLedger.record(count("kf_syslog_event", 1000, 1000, 0, 0, 0));
            RunLedger.error("incomplete scan: source count shrank");
            return 7;
        });
        ExtractionRun r = recorded.get(0);
        assertEquals("INCOMPLETE", r.status());
        assertEquals(1000, r.inserted());
        assertEquals("incomplete scan: source count shrank", r.errorMessage());
    }

    @Test
    void discardedRunsAndCallsOutsideARunRecordNothing() {
        RunLedger.run("bogus", () -> {
            RunLedger.discard();
            return 2;
        });
        RunLedger.record(count("kf_x", 1, 1, 0, 0, 0));
        RunLedger.error("ignored");
        assertTrue(recorded.isEmpty());
        assertNull(RunLedger.currentRunId());
        assertNotEquals(RunLedger.runIdOrNew(), RunLedger.runIdOrNew(), "outside a run: a fresh id each call");
    }

    @Test
    void buildRunSumsAllTables() {
        RunContext ctx = new RunContext("00000000-0000-0000-0000-000000000001", "c", "kfagent-rest",
                Instant.parse("2026-10-12T00:00:00Z"));
        ctx.addEntity(count("a", 10, 4, 5, 1, 0));
        ctx.addEntity(count("b", 3, 3, 0, 0, 2));
        ExtractionRun r = RunLedger.buildRun(ctx, 0, Instant.parse("2026-10-12T00:00:01.5Z"));
        assertEquals(13, r.extracted());
        assertEquals(7, r.inserted());
        assertEquals(5, r.updated());
        assertEquals(1, r.failed());
        assertEquals("COMPLETED_WITH_FAILURES", r.status(), "exit 0 but a row failed");
        assertEquals(1500L, r.durationMs());
    }
}

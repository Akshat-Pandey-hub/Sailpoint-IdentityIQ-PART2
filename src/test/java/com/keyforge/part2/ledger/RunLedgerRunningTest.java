package com.keyforge.part2.ledger;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** F6: the row written when a run starts, and the caller's request recorded with the run. */
class RunLedgerRunningTest {

    @Test
    void theStartRowIsRunningWithNoEndOrCountsYet() {
        RunContext ctx = new RunContext("00000000-0000-0000-0000-0000000000bb", "import-identity", "kfagent-rest",
                Instant.parse("2026-10-31T10:00:00Z"));
        ctx.setRequestJson("{\"start\":1000,\"limit\":500,\"modifiedAfter\":null,\"filters\":{}}");
        ExtractionRun r = RunLedger.running(ctx);
        assertEquals("RUNNING", r.status());
        assertNull(r.endedAt());
        assertNull(r.extracted());
        assertEquals("{\"start\":1000,\"limit\":500,\"modifiedAfter\":null,\"filters\":{}}", r.requestJson());
    }

    @Test
    void theFinishedRowKeepsTheRequest() {
        RunContext ctx = new RunContext("00000000-0000-0000-0000-0000000000cc", "import-role", "kfagent-rest",
                Instant.parse("2026-10-31T10:00:00Z"));
        ctx.setRequestJson("{\"start\":0,\"limit\":2}");
        ExtractionRun done = RunLedger.buildRun(ctx, 0, Instant.parse("2026-10-31T10:00:01Z"));
        assertEquals("SUCCESS", done.status());
        assertEquals("{\"start\":0,\"limit\":2}", done.requestJson());
        assertNull(RunLedger.buildRun(new RunContext("00000000-0000-0000-0000-0000000000dd", "import-role",
                "kfagent-rest", Instant.now()), 0, Instant.now()).requestJson(), "a full run has no request");
    }
}

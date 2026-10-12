package com.keyforge.part2;

import com.keyforge.part2.ingest.ImportResult;
import com.keyforge.part2.ingest.SoftDeleteSweeper;
import com.keyforge.part2.ledger.ExtractionRun;
import com.keyforge.part2.ledger.RunContext;
import com.keyforge.part2.ledger.RunLedger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Main's ledger wiring: usage errors are not runs; per-table counts carry the sweep outcome. */
class MainLedgerTest {

    @Test
    void anUnknownCommandIsAUsageErrorAndIsNotRecorded() {
        List<ExtractionRun> recorded = new ArrayList<>();
        RunLedger.Sink previous = RunLedger.useSink(recorded::add);
        try {
            assertEquals(2, RunLedger.run("import-nope", () -> Main.dispatch("import-nope")));
            assertEquals(2, RunLedger.run("", () -> Main.dispatch("")));
            assertTrue(recorded.isEmpty());
        } finally {
            RunLedger.useSink(previous);
        }
    }

    @Test
    void incrementalIsOfferedOnlyForTheFiveModifiedAfterEntitiesAndIsNotRecordedOtherwise() {
        assertEquals(java.util.Set.of("import-identity", "import-entitlement", "import-application", "import-account",
                "import-role"), Main.INCREMENTAL_WRITERS.keySet());
        List<ExtractionRun> recorded = new ArrayList<>();
        RunLedger.Sink previous = RunLedger.useSink(recorded::add);
        try {
            assertEquals(2, RunLedger.run("import-identity-role --incremental", () -> Main.dispatch(
                    "import-identity-role", com.keyforge.part2.incremental.ExtractionMode.INCREMENTAL)),
                    "an unsupported --incremental is a usage error, never a silent full import");
            assertTrue(recorded.isEmpty());
        } finally {
            RunLedger.useSink(previous);
        }
        var identity = Main.identityWriter("iiq_native_part2");
        assertEquals("identities", identity.resource());
        assertEquals("iiq_native_part2.kf_identity", identity.targetTable());
    }

    @Test
    void countsUseTheUnqualifiedTableAndTheSweepOutcome() {
        ImportResult r = new ImportResult("IdentityEntitlement", "iiq_native_part2.kf_identity_entitlement");
        r.countFetched(3);
        r.countInserted();
        r.countUpdated();
        r.countFailure("x", "boom");
        RunContext.EntityCount plain = Main.counts("iiq_native_part2.kf_identity_entitlement", r, null);
        assertEquals("kf_identity_entitlement", plain.entity());
        assertEquals(3, plain.extracted());
        assertEquals(1, plain.inserted());
        assertEquals(1, plain.updated());
        assertEquals(1, plain.failed());
        assertNull(plain.sweepStatus());

        SoftDeleteSweeper.Result sweep = new SoftDeleteSweeper.Result("kf_identity_entitlement",
                SoftDeleteSweeper.Status.SWEPT, 3, 2, 1, "verified");
        RunContext.EntityCount swept = Main.counts("s.kf_identity_entitlement", r, sweep);
        assertEquals("SWEPT", swept.sweepStatus());
        assertEquals(2, swept.marked());
        assertEquals(1, swept.revived());
    }
}

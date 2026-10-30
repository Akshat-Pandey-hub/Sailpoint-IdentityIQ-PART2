package com.keyforge.part2;

import com.keyforge.part2.ingest.SoftDeleteSweeper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a full run's deletion marking maps to the exit code (and so to the ledger status). */
class DeletionExitCodeTest {

    private static SoftDeleteSweeper.Result result(SoftDeleteSweeper.Status status) {
        return new SoftDeleteSweeper.Result("kf_x", status, 10, 0, 0, "note");
    }

    @Test
    void theSafetyLimitIsReportedAsIncomplete() {
        assertEquals(7, Main.printDeletions(result(SoftDeleteSweeper.Status.SKIPPED_SAFETY_LIMIT), 0),
                "the deletion state may be stale: INCOMPLETE");
    }

    @Test
    void otherwiseTheRowFailureCodeApplies() {
        assertEquals(0, Main.printDeletions(result(SoftDeleteSweeper.Status.SWEPT), 0));
        assertEquals(6, Main.printDeletions(result(SoftDeleteSweeper.Status.SKIPPED_ROW_FAILURES), 2));
        assertEquals(0, Main.printDeletions(result(SoftDeleteSweeper.Status.SKIPPED_EMPTY_KEEP), 0));
    }
}

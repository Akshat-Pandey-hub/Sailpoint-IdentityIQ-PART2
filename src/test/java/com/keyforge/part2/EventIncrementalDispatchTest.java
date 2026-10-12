package com.keyforge.part2;

import com.keyforge.part2.incremental.ExtractionMode;
import com.keyforge.part2.ledger.ExtractionRun;
import com.keyforge.part2.ledger.RunLedger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage-1 dispatch: which commands accept {@code --incremental}, and that everything else is still refused. */
class EventIncrementalDispatchTest {

    @Test
    void theEventCommandsAcceptIncrementalAndTheOriginalFiveAreUnchanged() {
        assertEquals(Set.of("import-audit-event", "import-workitem-archive", "import-certification-archive",
                "import-access-history", "import-access-history-identity-event",
                "import-access-history-certification", "import-access-history-role-event"),
                Main.EVENT_INCREMENTAL_WRITERS.keySet());
        assertEquals("import-syslog-event", Main.SYSLOG_COMMAND);
        assertEquals(Set.of("import-identity", "import-entitlement", "import-application", "import-account",
                "import-role"), Main.INCREMENTAL_WRITERS.keySet());
        for (String c : Main.EVENT_INCREMENTAL_WRITERS.keySet()) {
            assertTrue(!Main.INCREMENTAL_WRITERS.containsKey(c), c + " is not a modified-cursor entity");
        }
    }

    @Test
    void stillUnsupportedCommandsAreAUsageErrorNotASilentFullImport() {
        List<ExtractionRun> recorded = new ArrayList<>();
        RunLedger.Sink previous = RunLedger.useSink(recorded::add);
        try {
            for (String c : List.of("import-policy", "import-entitlement-assignment", "import-workgroup")) {
                assertEquals(2, RunLedger.run(c + " --incremental",
                        () -> Main.dispatch(c, ExtractionMode.INCREMENTAL)), c);
            }
            assertTrue(recorded.isEmpty(), "usage errors are not recorded as runs");
        } finally {
            RunLedger.useSink(previous);
            RunLedger.discard();
        }
    }
}

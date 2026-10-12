package com.keyforge.part2;

import com.keyforge.part2.incremental.ExtractionMode;
import com.keyforge.part2.ledger.ExtractionRun;
import com.keyforge.part2.ledger.RunLedger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage-2 dispatch: changed-cursor commands, parent + children families, and child commands' usage error. */
class ChangedIncrementalDispatchTest {

    @Test
    void changedCursorCommandsAndFamilies() {
        assertEquals(Set.of("import-work-item", "import-task-result", "import-certification-item",
                "import-certification-entity", "import-identity-entitlement"),
                Main.CHANGED_INCREMENTAL_WRITERS.keySet());
        assertEquals(Set.of("import-identity-request", "import-provisioning-transaction", "import-account-entitlement"),
                Main.INCREMENTAL_FAMILIES.keySet());
        assertEquals(2, Main.INCREMENTAL_FAMILIES.get("import-identity-request").children().size());
        assertEquals(1, Main.INCREMENTAL_FAMILIES.get("import-provisioning-transaction").children().size());

        Set<String> all = new HashSet<>();
        for (Set<String> group : List.of(Main.INCREMENTAL_WRITERS.keySet(), Main.CHANGED_INCREMENTAL_WRITERS.keySet(),
                Main.INCREMENTAL_FAMILIES.keySet(), Main.EVENT_INCREMENTAL_WRITERS.keySet())) {
            for (String c : group) {
                assertTrue(all.add(c), c + " appears in more than one incremental group");
            }
        }
        assertEquals(5 + 5 + 3 + 7, all.size());
    }

    @Test
    void accountEntitlementFamilyHasItsOwnWatermarkIndependentOfImportAccount() {
        Main.IncrementalFamily f = Main.INCREMENTAL_FAMILIES.get("import-account-entitlement");
        com.keyforge.part2.ingest.EntityWriter parent = f.parent().apply("s");
        com.keyforge.part2.ingest.EntityWriter child = f.children().get(0).apply("s");
        assertEquals("s.kf_account", parent.targetTable());
        assertEquals("accounts", parent.resource());
        assertEquals("s.kf_account_entitlement", child.targetTable());
        assertEquals("account-entitlements", child.resource());
        String key = com.keyforge.part2.incremental.FamilyIncrementalImportService.familyKey(parent, List.of(child));
        assertEquals("native:kf_account+kf_account_entitlement", key);
        assertTrue(!key.equals(com.keyforge.part2.incremental.WatermarkService.watermarkKey("s.kf_account")),
                "import-account --incremental keeps its own watermark");
    }

    @Test
    void childCommandsPointToTheirParentAndAreNotRecorded() {
        assertEquals("import-identity-request", Main.INCREMENTAL_CHILD_OF.get("import-identity-request-item"));
        assertEquals("import-identity-request", Main.INCREMENTAL_CHILD_OF.get("import-identity-request-approval"));
        assertEquals("import-provisioning-transaction", Main.INCREMENTAL_CHILD_OF.get("import-provisioning-item"));
        List<ExtractionRun> recorded = new ArrayList<>();
        RunLedger.Sink previous = RunLedger.useSink(recorded::add);
        try {
            for (String c : Main.INCREMENTAL_CHILD_OF.keySet()) {
                assertEquals(2, RunLedger.run(c + " --incremental", () -> Main.dispatch(c, ExtractionMode.INCREMENTAL)),
                        c);
            }
            assertTrue(recorded.isEmpty(), "a usage error is not recorded as a run");
        } finally {
            RunLedger.useSink(previous);
            RunLedger.discard();
        }
    }
}

package com.keyforge.part2;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage-2 dispatch: the six changed-cursor commands, disjoint from the other incremental groups. */
class ChangedIncrementalDispatchTest {

    @Test
    void theSixChangedCursorCommands() {
        assertEquals(Set.of("import-identity-request", "import-provisioning-transaction", "import-work-item",
                "import-task-result", "import-certification-item", "import-certification-entity"),
                Main.CHANGED_INCREMENTAL_WRITERS.keySet());
        Set<String> all = new HashSet<>(Main.INCREMENTAL_WRITERS.keySet());
        assertTrue(all.addAll(Main.CHANGED_INCREMENTAL_WRITERS.keySet()));
        int before = all.size();
        all.addAll(Main.EVENT_INCREMENTAL_WRITERS.keySet());
        assertEquals(before + Main.EVENT_INCREMENTAL_WRITERS.size(), all.size(), "groups are disjoint");
        assertEquals(5 + 6 + 7, all.size());
    }

    @Test
    void childDatasetsAreStillFullOnly() {
        for (String c : new String[]{"import-identity-request-item", "import-identity-request-approval",
                "import-provisioning-item"}) {
            assertTrue(!Main.CHANGED_INCREMENTAL_WRITERS.containsKey(c) && !Main.INCREMENTAL_WRITERS.containsKey(c)
                    && !Main.EVENT_INCREMENTAL_WRITERS.containsKey(c), c);
        }
    }
}

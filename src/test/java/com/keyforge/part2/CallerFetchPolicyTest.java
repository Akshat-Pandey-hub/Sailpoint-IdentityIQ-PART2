package com.keyforge.part2;

import com.keyforge.part2.http.FetchOptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallerFetchPolicyTest {

    @Test
    void onlyAnUnscopedFullRunMayUseSnapshotSweep() {
        assertTrue(Main.shouldRunSnapshotSweep(FetchOptions.none(), true));
        assertFalse(Main.shouldRunSnapshotSweep(new FetchOptions(0, 10, null, Map.of()), true));
        assertFalse(Main.shouldRunSnapshotSweep(new FetchOptions(null, null, null, Map.of("type", "employee")), true));
        assertFalse(Main.shouldRunSnapshotSweep(new FetchOptions(null, null, "2026-10-20T00:00:00Z", Map.of()), true));
        assertFalse(Main.shouldRunSnapshotSweep(FetchOptions.none(), false));
    }

    @Test
    void callerScopeIncludesSqlPageSnapshotsWithoutEnablingPartialSnapshotReplacement() {
        assertTrue(Main.supportsCallerFetchOptions("import-identity"));
        assertTrue(Main.supportsCallerFetchOptions("import-certification"));
        assertTrue(Main.supportsCallerFetchOptions("import-syslog-event"));
        assertTrue(Main.supportsCallerFetchOptions("import-entitlement-assignment"));
        assertTrue(Main.supportsCallerFetchOptions("import-entitlement-certification"));
        assertTrue(Main.supportsCallerFetchOptions("import-entitlement-certification-status"));
        assertTrue(Main.supportsCallerFetchOptions("import-workgroup-members"));
        assertTrue(Main.supportsCallerFetchOptions("import-identity-request-item"));
        assertTrue(Main.supportsCallerFetchOptions("import-identity-request-approval"));
        assertTrue(Main.supportsCallerFetchOptions("import-provisioning-item"));
        assertTrue(Main.supportsCallerFetchOptions("import-access-history"));
        assertFalse(Main.supportsCallerFetchOptions("derive-native-events"));
        assertFalse(Main.supportsCallerFetchOptions("reconcile-native-workitems"));
        assertFalse(Main.supportsCallerFetchOptions("import-not-a-real-command"));
    }

    @Test
    void callerModifiedAfterUsesOnlyExistingEndpointCapabilities() {
        assertTrue(Main.supportsCallerModifiedAfter("import-identity"));
        assertTrue(Main.supportsCallerModifiedAfter("import-identity-request-item"));
        assertTrue(Main.supportsCallerModifiedAfter("import-identity-request-approval"));
        assertTrue(Main.supportsCallerModifiedAfter("import-provisioning-item"));
        assertTrue(Main.supportsCallerModifiedAfter("import-syslog-event"));
        assertFalse(Main.supportsCallerModifiedAfter("import-certification"));
        assertFalse(Main.supportsCallerModifiedAfter("import-entitlement-assignment"));
        assertTrue(Main.fetchFilterFields("import-access-history").contains("name"));
        assertFalse(Main.supportsCallerModifiedAfter("import-workgroup-members"));
        assertTrue(Main.fetchFilterFields("import-entitlement-assignment").isEmpty());
        assertEquals("entitlement-assignment", Main.fetchResource("import-entitlement-assignment"));
    }
}

package com.keyforge.part2.event;

import com.keyforge.part2.config.AppConfig;
import com.keyforge.part2.config.ConfigException;
import com.keyforge.part2.ledger.ExtractionRun;
import com.keyforge.part2.ledger.RunLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The derive-native-events command's plumbing: it is ledgered as {@code derived}, and its configuration needs
 * PostgreSQL only (KFAGENT_BASE_URL optional), while every KF Agent import still requires KFAGENT_BASE_URL.
 */
class DeriveCommandConfigTest {

    private static final String[] KEYS = {"KFAGENT_BASE_URL", "PG_HOST", "PG_DATABASE", "PG_USERNAME", "PG_PASSWORD"};
    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void isolateProperties() {
        for (String k : KEYS) {
            // environment variables win over -D properties; these tests need them unset
            assumeTrue(System.getenv(k) == null || System.getenv(k).isBlank(), k + " is set in the environment");
            saved.put(k, System.getProperty(k));
            System.clearProperty(k);
        }
    }

    @AfterEach
    void restoreProperties() {
        saved.forEach((k, v) -> {
            if (v == null) System.clearProperty(k); else System.setProperty(k, v);
        });
    }

    private static void setPg() {
        System.setProperty("PG_HOST", "localhost");
        System.setProperty("PG_DATABASE", "db");
        System.setProperty("PG_USERNAME", "u");
        System.setProperty("PG_PASSWORD", "p");
    }

    @Test
    void databaseOnlyConfigDoesNotNeedTheKfAgent() {
        setPg();
        AppConfig c = AppConfig.loadDatabaseOnly();
        assertNull(c.kfAgentBaseUrl());
        assertTrue(c.describe().contains("KF Agent: (not used)"), c.describe());
        assertTrue(!c.describe().contains("p'") && !c.describe().contains("=p"), "no password in the description");
    }

    @Test
    void importsStillRequireTheKfAgent() {
        setPg();
        ConfigException e = assertThrows(ConfigException.class, AppConfig::load);
        assertTrue(e.getMessage().contains("KFAGENT_BASE_URL"), e.getMessage());
    }

    @Test
    void databaseOnlyConfigStillRequiresPostgresAndValidatesAGivenUrl() {
        ConfigException missing = assertThrows(ConfigException.class, AppConfig::loadDatabaseOnly);
        assertTrue(missing.getMessage().contains("PG_HOST"), missing.getMessage());
        assertTrue(!missing.getMessage().contains("Missing required configuration: KFAGENT_BASE_URL"));

        setPg();
        System.setProperty("KFAGENT_BASE_URL", "ftp://x");
        assertThrows(ConfigException.class, AppConfig::loadDatabaseOnly);
        System.setProperty("KFAGENT_BASE_URL", "http://127.0.0.1:8100/");
        assertEquals("http://127.0.0.1:8100", AppConfig.loadDatabaseOnly().kfAgentBaseUrl());
    }

    @Test
    void deriveCommandIsLedgeredAsDerivedWithMeasuredCounts() {
        List<ExtractionRun> recorded = new ArrayList<>();
        RunLedger.Sink previous = RunLedger.useSink(recorded::add);
        try {
            RunLedger.run("derive-native-events", () -> {
                RunLedger.record(new com.keyforge.part2.ledger.RunContext.EntityCount("kf_event", 6, 2, 0, 0, 4,
                        null, null, null));
                return 0;
            });
            RunLedger.run("derive-native-events", () -> 5);
        } finally {
            RunLedger.useSink(previous);
            RunLedger.discard();
        }
        ExtractionRun ok = recorded.get(0);
        assertEquals("derived", ok.sourceInterface());
        assertEquals("SUCCESS", ok.status());
        assertEquals(6, ok.extracted());
        assertEquals(2, ok.inserted());
        assertEquals(0, ok.updated(), "already-present events are skipped, never counted as updates");
        assertTrue(ok.entityCountsJson().contains("\"skipped\":4"), ok.entityCountsJson());
        ExtractionRun failed = recorded.get(1);
        assertEquals("derived", failed.sourceInterface());
        assertEquals("FAILED", failed.status());
    }
}

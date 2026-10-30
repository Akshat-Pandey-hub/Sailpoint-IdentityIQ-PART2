package com.keyforge.part2.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The optional client certificate (mutual TLS) settings: unset changes nothing; set, they must come as a pair, point
 * at a readable file and go with an https base URL; the password never appears in the startup description.
 */
class ClientCertificateConfigTest {

    private static final String[] KEYS = {"KFAGENT_BASE_URL", "KFAGENT_USERNAME", "KFAGENT_PASSWORD",
            "KFAGENT_CLIENT_KEYSTORE", "KFAGENT_CLIENT_KEYSTORE_PASSWORD", "PG_HOST", "PG_DATABASE", "PG_USERNAME",
            "PG_PASSWORD", "KFAGENT_REPORT_PAGE_SIZE"};
    private static final String SECRET = "s3cret-keystore-pass";
    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void isolateProperties() {
        for (String k : KEYS) {
            // environment variables win over -D properties; these tests need them unset
            assumeTrue(System.getenv(k) == null || System.getenv(k).isBlank(), k + " is set in the environment");
            saved.put(k, System.getProperty(k));
            System.clearProperty(k);
        }
        System.setProperty("KFAGENT_USERNAME", "agent-user");
        System.setProperty("KFAGENT_PASSWORD", "agent-pass");
        System.setProperty("PG_HOST", "localhost");
        System.setProperty("PG_DATABASE", "db");
        System.setProperty("PG_USERNAME", "u");
        System.setProperty("PG_PASSWORD", "p");
    }

    @AfterEach
    void restoreProperties() {
        saved.forEach((k, v) -> {
            if (v == null) System.clearProperty(k); else System.setProperty(k, v);
        });
    }

    static String keystore() throws Exception {
        return Path.of(ClientCertificateConfigTest.class.getResource("/mtls/client.p12").toURI()).toString();
    }

    @Test
    void kfAgentCredentialsAreRequiredToCallTheAgent() {
        System.setProperty("KFAGENT_BASE_URL", "http://127.0.0.1:8100");
        System.clearProperty("KFAGENT_PASSWORD");
        ConfigException e = assertThrows(ConfigException.class, AppConfig::load);
        assertTrue(e.getMessage().contains("KFAGENT_PASSWORD"), e.getMessage());

        System.setProperty("KFAGENT_PASSWORD", "agent-pass");
        System.clearProperty("KFAGENT_USERNAME");
        e = assertThrows(ConfigException.class, AppConfig::load);
        assertTrue(e.getMessage().contains("KFAGENT_USERNAME"), e.getMessage());

        AppConfig db = AppConfig.loadDatabaseOnly();
        assertFalse(db.hasBasicAuth(), "database-only commands never call the agent and need no credentials");
    }

    @Test
    void sqlReportPageSizeDefaultsToTheAgentMaximumAndIsBounded() {
        System.setProperty("KFAGENT_BASE_URL", "http://127.0.0.1:8100");
        assertEquals(AppConfig.MAX_REPORT_PAGE_SIZE, AppConfig.load().reportPageSize());
        assertTrue(AppConfig.load().hasBasicAuth());
        System.setProperty("KFAGENT_REPORT_PAGE_SIZE", "50000");
        assertEquals(50000, AppConfig.load().reportPageSize());
        System.setProperty("KFAGENT_REPORT_PAGE_SIZE", "200001");
        assertThrows(ConfigException.class, AppConfig::load);
        System.setProperty("KFAGENT_REPORT_PAGE_SIZE", "0");
        assertThrows(ConfigException.class, AppConfig::load);
    }

    @Test
    void unsetMeansNoClientCertificateExactlyAsBefore() {
        System.setProperty("KFAGENT_BASE_URL", "http://127.0.0.1:8100");
        AppConfig c = AppConfig.load();
        assertFalse(c.hasClientCertificate());
        assertNull(c.kfAgentClientKeystore());
        assertFalse(c.describe().contains("client-cert"), c.describe());
    }

    @Test
    void bothSetWithHttpsEnablesItAndNeverPrintsThePassword() throws Exception {
        System.setProperty("KFAGENT_BASE_URL", "https://ec0124.keyforge.ngrok.io/");
        System.setProperty("KFAGENT_CLIENT_KEYSTORE", "  " + keystore() + " ");
        System.setProperty("KFAGENT_CLIENT_KEYSTORE_PASSWORD", SECRET);
        AppConfig c = AppConfig.load();
        assertTrue(c.hasClientCertificate());
        assertEquals(keystore(), c.kfAgentClientKeystore(), "path is trimmed");
        assertEquals(SECRET, c.kfAgentClientKeystorePassword());
        assertEquals("https://ec0124.keyforge.ngrok.io", c.kfAgentBaseUrl());
        assertTrue(c.describe().contains("(client-cert)"), c.describe());
        assertFalse(c.describe().contains(SECRET), "no keystore password in the description");
    }

    @Test
    void keystoreWithoutPasswordIsRefused() throws Exception {
        System.setProperty("KFAGENT_BASE_URL", "https://agent.example");
        System.setProperty("KFAGENT_CLIENT_KEYSTORE", keystore());
        ConfigException e = assertThrows(ConfigException.class, AppConfig::load);
        assertTrue(e.getMessage().contains("must be set together"), e.getMessage());
    }

    @Test
    void passwordWithoutKeystoreIsRefused() {
        System.setProperty("KFAGENT_BASE_URL", "https://agent.example");
        System.setProperty("KFAGENT_CLIENT_KEYSTORE_PASSWORD", SECRET);
        ConfigException e = assertThrows(ConfigException.class, AppConfig::load);
        assertTrue(e.getMessage().contains("must be set together"), e.getMessage());
        assertFalse(e.getMessage().contains(SECRET), "no password in the error");
    }

    @Test
    void missingFileIsRefused() {
        System.setProperty("KFAGENT_BASE_URL", "https://agent.example");
        System.setProperty("KFAGENT_CLIENT_KEYSTORE", "D:/no/such/client.p12");
        System.setProperty("KFAGENT_CLIENT_KEYSTORE_PASSWORD", SECRET);
        ConfigException e = assertThrows(ConfigException.class, AppConfig::load);
        assertTrue(e.getMessage().contains("not a readable file"), e.getMessage());
    }

    @Test
    void plainHttpUrlWithACertificateIsRefused() throws Exception {
        System.setProperty("KFAGENT_BASE_URL", "http://127.0.0.1:8100");
        System.setProperty("KFAGENT_CLIENT_KEYSTORE", keystore());
        System.setProperty("KFAGENT_CLIENT_KEYSTORE_PASSWORD", SECRET);
        ConfigException e = assertThrows(ConfigException.class, AppConfig::load);
        assertTrue(e.getMessage().contains("not https"), e.getMessage());
    }

    @Test
    void databaseOnlyCommandsAndTheRunLedgerIgnoreTheCertificateSettings() {
        System.setProperty("KFAGENT_CLIENT_KEYSTORE", "D:/no/such/client.p12");
        AppConfig c = AppConfig.loadDatabaseOnly();
        assertFalse(c.hasClientCertificate(), "no KF Agent call on this path, so nothing to validate or present");

        // even with the agent URL set (as on a deployed server), a broken certificate setting must not stop the ledger
        // from recording the failed run
        System.setProperty("KFAGENT_BASE_URL", "https://ec0124.keyforge.ngrok.io");
        assertFalse(AppConfig.loadDatabaseOnly().hasClientCertificate());
    }
}

package com.keyforge.part2.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime configuration for Part 2, resolved from environment variables (with JVM {@code -D} system-property
 * fallback). Nothing is hardcoded; no real credentials live in the code. Two groups:
 *
 * <ul>
 *   <li><b>KF Agent (HTTP source):</b> {@code KFAGENT_BASE_URL} (required, e.g. http://127.0.0.1:8100),
 *       optional {@code KFAGENT_USERNAME}/{@code KFAGENT_PASSWORD} (Basic Auth, only if the agent is put
 *       behind an authenticating proxy), {@code KFAGENT_PAGE_SIZE} (default 500),
 *       {@code HTTP_TIMEOUT_SECONDS} (default 60). Optional client certificate (mutual TLS), for an agent reached
 *       through a gateway that demands one (e.g. an ngrok edge): {@code KFAGENT_CLIENT_KEYSTORE} (path to a PKCS12
 *       {@code .p12} file holding the certificate and its private key) + {@code KFAGENT_CLIENT_KEYSTORE_PASSWORD};
 *       both or neither, and only with an {@code https://} base URL. Unset = no client certificate (as before).</li>
 *   <li><b>PostgreSQL (destination):</b> {@code PG_HOST} (required), {@code PG_PORT} (default 5432),
 *       {@code PG_DATABASE} (required), {@code PG_DEST_SCHEMA} (default iiq_native_part2),
 *       {@code PG_USERNAME} (required), {@code PG_PASSWORD} (required).</li>
 * </ul>
 */
public final class AppConfig {

    private final String kfAgentBaseUrl;
    private final String kfAgentUsername; // nullable
    private final String kfAgentPassword; // nullable
    private final int pageSize;
    private final int httpTimeoutSeconds;
    private final String kfAgentClientKeystore;         // nullable: no client certificate
    private final String kfAgentClientKeystorePassword; // nullable together with the keystore

    private final String pgHost;
    private final String pgPort;
    private final String pgDatabase;
    private final String pgSchema;
    private final String pgUsername;
    private final String pgPassword;

    private AppConfig(String kfAgentBaseUrl, String kfAgentUsername, String kfAgentPassword, int pageSize,
                      int httpTimeoutSeconds, String kfAgentClientKeystore, String kfAgentClientKeystorePassword,
                      String pgHost, String pgPort, String pgDatabase, String pgSchema,
                      String pgUsername, String pgPassword) {
        this.kfAgentBaseUrl = kfAgentBaseUrl;
        this.kfAgentUsername = kfAgentUsername;
        this.kfAgentPassword = kfAgentPassword;
        this.pageSize = pageSize;
        this.httpTimeoutSeconds = httpTimeoutSeconds;
        this.kfAgentClientKeystore = kfAgentClientKeystore;
        this.kfAgentClientKeystorePassword = kfAgentClientKeystorePassword;
        this.pgHost = pgHost;
        this.pgPort = pgPort;
        this.pgDatabase = pgDatabase;
        this.pgSchema = pgSchema;
        this.pgUsername = pgUsername;
        this.pgPassword = pgPassword;
    }

    public static AppConfig load() {
        return load(true);
    }

    /**
     * Configuration for a command that only reads/writes PostgreSQL (e.g. deriving events from loaded tables, or
     * recording the run ledger): {@code KFAGENT_BASE_URL} is not required ({@link #kfAgentBaseUrl()} is then
     * {@code null}); all PostgreSQL settings are resolved and validated exactly as by {@link #load()}.
     */
    public static AppConfig loadDatabaseOnly() {
        return load(false);
    }

    private static AppConfig load(boolean requireKfAgent) {
        List<String> missing = new ArrayList<>();
        String base = requireKfAgent ? required("KFAGENT_BASE_URL", missing) : nullable("KFAGENT_BASE_URL");
        String pgHost = required("PG_HOST", missing);
        String pgDatabase = required("PG_DATABASE", missing);
        String pgUsername = required("PG_USERNAME", missing);
        String pgPassword = required("PG_PASSWORD", missing);
        if (!missing.isEmpty()) {
            throw new ConfigException("Missing required configuration: " + String.join(", ", missing)
                    + ". Set these as environment variables (e.g. export KFAGENT_BASE_URL=http://127.0.0.1:8100)"
                    + " or JVM system properties (-DKFAGENT_BASE_URL=...).");
        }

        String url = null;
        if (base != null) {
            url = base.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new ConfigException("KFAGENT_BASE_URL must be an http(s) URL but was: " + base);
            }
        }

        String pgPort = orDefault("PG_PORT", "5432");
        String pgSchema = SchemaName.validate(orDefault("PG_DEST_SCHEMA", SchemaName.DEFAULT));
        int pageSize = intOrDefault("KFAGENT_PAGE_SIZE", 500);
        int httpTimeout = intOrDefault("HTTP_TIMEOUT_SECONDS", 60);

        String keystore = nullable("KFAGENT_CLIENT_KEYSTORE");
        String keystorePassword = nullable("KFAGENT_CLIENT_KEYSTORE_PASSWORD");
        if (!requireKfAgent) {
            // database-only commands and the run ledger never call the KF Agent: no certificate to present or validate
            keystore = null;
            keystorePassword = null;
        } else {
            validateClientCertificate(url, keystore, keystorePassword);
            keystore = keystore == null ? null : keystore.trim();
        }

        return new AppConfig(url, nullable("KFAGENT_USERNAME"), nullable("KFAGENT_PASSWORD"),
                pageSize, httpTimeout, keystore, keystorePassword, pgHost.trim(), pgPort, pgDatabase.trim(), pgSchema,
                pgUsername.trim(), pgPassword);
    }

    /** Both or neither; the file must be readable; a certificate is only ever presented over https. */
    private static void validateClientCertificate(String url, String keystore, String password) {
        if (keystore == null && password == null) {
            return;
        }
        if (keystore == null || password == null) {
            throw new ConfigException("KFAGENT_CLIENT_KEYSTORE and KFAGENT_CLIENT_KEYSTORE_PASSWORD must be set together"
                    + " (only " + (keystore == null ? "KFAGENT_CLIENT_KEYSTORE_PASSWORD" : "KFAGENT_CLIENT_KEYSTORE")
                    + " is set).");
        }
        java.nio.file.Path file = java.nio.file.Path.of(keystore.trim());
        if (!java.nio.file.Files.isRegularFile(file) || !java.nio.file.Files.isReadable(file)) {
            throw new ConfigException("KFAGENT_CLIENT_KEYSTORE is not a readable file: " + keystore);
        }
        if (!url.startsWith("https://")) {
            throw new ConfigException("KFAGENT_CLIENT_KEYSTORE is set but KFAGENT_BASE_URL is not https (" + url
                    + "): a client certificate is only presented over TLS.");
        }
    }

    public String kfAgentBaseUrl() { return kfAgentBaseUrl; }
    public String kfAgentUsername() { return kfAgentUsername; }
    public String kfAgentPassword() { return kfAgentPassword; }
    public boolean hasBasicAuth() { return kfAgentUsername != null && kfAgentPassword != null; }
    public int pageSize() { return pageSize; }
    public int httpTimeoutSeconds() { return httpTimeoutSeconds; }
    public String kfAgentClientKeystore() { return kfAgentClientKeystore; }
    public String kfAgentClientKeystorePassword() { return kfAgentClientKeystorePassword; }
    public boolean hasClientCertificate() { return kfAgentClientKeystore != null; }

    public String pgSchema() { return pgSchema; }
    public String pgUsername() { return pgUsername; }
    public String pgPassword() { return pgPassword; }
    public String jdbcUrl() { return "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDatabase; }

    /** A one-line, credential-free description for startup logging. */
    public String describe() {
        return "KF Agent: " + (kfAgentBaseUrl == null ? "(not used)" : kfAgentBaseUrl)
                + (hasBasicAuth() ? " (basic-auth)" : "")
                + (hasClientCertificate() ? " (client-cert)" : "")
                + " | PostgreSQL: " + jdbcUrl() + " (user '" + pgUsername + "', dest schema '" + pgSchema + "')"
                + " | pageSize=" + pageSize + ", httpTimeout=" + httpTimeoutSeconds + "s";
    }

    // ── resolution helpers (env → system property) ──
    private static String resolve(String key) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) {
            v = System.getProperty(key);
        }
        return (v == null || v.trim().isEmpty()) ? null : v;
    }

    private static String required(String key, List<String> missing) {
        String v = resolve(key);
        if (v == null) {
            missing.add(key);
        }
        return v;
    }

    private static String nullable(String key) {
        return resolve(key);
    }

    private static String orDefault(String key, String def) {
        String v = resolve(key);
        return v == null ? def : v.trim();
    }

    private static int intOrDefault(String key, int def) {
        String v = resolve(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be an integer but was: " + v);
        }
    }
}

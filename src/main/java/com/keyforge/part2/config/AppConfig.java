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
 *       {@code HTTP_TIMEOUT_SECONDS} (default 60).</li>
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

    private final String pgHost;
    private final String pgPort;
    private final String pgDatabase;
    private final String pgSchema;
    private final String pgUsername;
    private final String pgPassword;

    private AppConfig(String kfAgentBaseUrl, String kfAgentUsername, String kfAgentPassword, int pageSize,
                      int httpTimeoutSeconds, String pgHost, String pgPort, String pgDatabase, String pgSchema,
                      String pgUsername, String pgPassword) {
        this.kfAgentBaseUrl = kfAgentBaseUrl;
        this.kfAgentUsername = kfAgentUsername;
        this.kfAgentPassword = kfAgentPassword;
        this.pageSize = pageSize;
        this.httpTimeoutSeconds = httpTimeoutSeconds;
        this.pgHost = pgHost;
        this.pgPort = pgPort;
        this.pgDatabase = pgDatabase;
        this.pgSchema = pgSchema;
        this.pgUsername = pgUsername;
        this.pgPassword = pgPassword;
    }

    public static AppConfig load() {
        List<String> missing = new ArrayList<>();
        String base = required("KFAGENT_BASE_URL", missing);
        String pgHost = required("PG_HOST", missing);
        String pgDatabase = required("PG_DATABASE", missing);
        String pgUsername = required("PG_USERNAME", missing);
        String pgPassword = required("PG_PASSWORD", missing);
        if (!missing.isEmpty()) {
            throw new ConfigException("Missing required configuration: " + String.join(", ", missing)
                    + ". Set these as environment variables (e.g. export KFAGENT_BASE_URL=http://127.0.0.1:8100)"
                    + " or JVM system properties (-DKFAGENT_BASE_URL=...).");
        }

        String url = base.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ConfigException("KFAGENT_BASE_URL must be an http(s) URL but was: " + base);
        }

        String pgPort = orDefault("PG_PORT", "5432");
        String pgSchema = SchemaName.validate(orDefault("PG_DEST_SCHEMA", SchemaName.DEFAULT));
        int pageSize = intOrDefault("KFAGENT_PAGE_SIZE", 500);
        int httpTimeout = intOrDefault("HTTP_TIMEOUT_SECONDS", 60);

        return new AppConfig(url, nullable("KFAGENT_USERNAME"), nullable("KFAGENT_PASSWORD"),
                pageSize, httpTimeout, pgHost.trim(), pgPort, pgDatabase.trim(), pgSchema,
                pgUsername.trim(), pgPassword);
    }

    public String kfAgentBaseUrl() { return kfAgentBaseUrl; }
    public String kfAgentUsername() { return kfAgentUsername; }
    public String kfAgentPassword() { return kfAgentPassword; }
    public boolean hasBasicAuth() { return kfAgentUsername != null && kfAgentPassword != null; }
    public int pageSize() { return pageSize; }
    public int httpTimeoutSeconds() { return httpTimeoutSeconds; }

    public String pgSchema() { return pgSchema; }
    public String pgUsername() { return pgUsername; }
    public String pgPassword() { return pgPassword; }
    public String jdbcUrl() { return "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDatabase; }

    /** A one-line, credential-free description for startup logging. */
    public String describe() {
        return "KF Agent: " + kfAgentBaseUrl + (hasBasicAuth() ? " (basic-auth)" : "")
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

package com.keyforge.part2.http;

import com.keyforge.part2.config.AppConfig;
import com.keyforge.part2.config.ConfigException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;

/**
 * Thin HTTP client for the Part 1 KF Agent REST endpoints. GETs one page of a resource
 * ({@code <base>/kfagent/<resource>?start=&limit=}) and returns the raw JSON body (a bare JSON array).
 * Read-only; no SailPoint/IIQ dependency. Optional HTTP Basic Auth is sent only when both
 * {@code KFAGENT_USERNAME} and {@code KFAGENT_PASSWORD} are configured (for an authenticating proxy in
 * front of the agent); the agent itself is unauthenticated.
 *
 * <p>When {@code KFAGENT_CLIENT_KEYSTORE} is configured, the client also presents that client certificate during the
 * TLS handshake (mutual TLS), as a gateway in front of the agent may require (e.g. an ngrok edge with mTLS). The
 * server is still verified against the JDK's standard trusted CAs. Basic Auth is sent as before, on top of it.
 */
public final class KfAgentClient {

    private final AppConfig config;
    private final HttpClient http;

    public KfAgentClient(AppConfig config) {
        this.config = config;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.httpTimeoutSeconds()));
        if (config.hasClientCertificate()) {
            builder.sslContext(clientCertificateContext(config.kfAgentClientKeystore(),
                    config.kfAgentClientKeystorePassword().toCharArray()));
        }
        this.http = builder.build();
    }

    /**
     * A TLS context that presents the client certificate in the PKCS12 file {@code keystorePath} and trusts the JDK's
     * standard root CAs (which include the CA of a public gateway such as ngrok's edge).
     *
     * @throws ConfigException when the file cannot be read or opened with {@code password} (the password is never
     *                         included in the message)
     */
    public static SSLContext clientCertificateContext(String keystorePath, char[] password) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(keystorePath))) {
                keyStore.load(in, password);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null); // the JDK's standard trusted CAs
            SSLContext ssl = SSLContext.getInstance("TLS"); // negotiates TLS 1.3 where the server offers it
            ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return ssl;
        } catch (IOException | GeneralSecurityException e) {
            throw new ConfigException("Cannot use the client certificate in KFAGENT_CLIENT_KEYSTORE (" + keystorePath
                    + "): " + e.getMessage() + ". Check that it is a PKCS12 (.p12) file and that"
                    + " KFAGENT_CLIENT_KEYSTORE_PASSWORD is its password.");
        }
    }

    /** One fetched page: the raw body plus the agent's {@code X-KF-Source-Count} header ({@code null} if absent). */
    public record Page(String body, Integer sourceCount) { }

    /** Response header in which the KF Agent reports the source population size (syslog-events). */
    public static final String SOURCE_COUNT_HEADER = "X-KF-Source-Count";

    /**
     * Fetches one page of {@code /kfagent/<resource>}.
     *
     * @param resource e.g. {@code "identities"}
     * @return the response body (expected to be a JSON array)
     * @throws KfAgentHttpException on a non-2xx status, timeout, or transport error
     */
    public String fetchPage(String resource, int start, int limit) {
        return fetch(resource, start, limit).body();
    }

    /**
     * Like {@link #fetchPage} but also returns the {@value #SOURCE_COUNT_HEADER} response header. A header that is
     * absent or not an integer is reported as {@code null}.
     */
    public Page fetch(String resource, int start, int limit) {
        return fetch(resource, start, limit, null);
    }

    /** Same, bounded to objects after {@code modifiedAfterIso} (an ISO-8601 instant; {@code null} = unbounded). */
    public Page fetch(String resource, int start, int limit, String modifiedAfterIso) {
        String url = config.kfAgentBaseUrl() + "/kfagent/" + resource
                + "?start=" + Math.max(0, start) + "&limit=" + limit
                + (modifiedAfterIso == null ? ""
                : "&modifiedAfter=" + java.net.URLEncoder.encode(modifiedAfterIso, StandardCharsets.UTF_8));
        HttpResponse<String> resp = get(url);
        return new Page(resp.body(), sourceCount(resp.headers().firstValue(SOURCE_COUNT_HEADER).orElse(null)));
    }

    /** Caller-scoped read: omitted window parameters intentionally let Part 1 return its complete filtered result. */
    public Page fetch(String resource, com.keyforge.part2.http.FetchOptions options) {
        java.util.List<String> query = new java.util.ArrayList<>();
        if (options.start() != null) {
            query.add("start=" + options.start());
            query.add("limit=" + options.limit());
        }
        if (options.modifiedAfter() != null) query.add("modifiedAfter=" + encode(options.modifiedAfter()));
        options.filters().forEach((key, value) -> query.add(encode(key) + "=" + encode(value)));
        String url = config.kfAgentBaseUrl() + "/kfagent/" + resource
                + (query.isEmpty() ? "" : "?" + String.join("&", query));
        HttpResponse<String> resp = get(url);
        return new Page(resp.body(), sourceCount(resp.headers().firstValue(SOURCE_COUNT_HEADER).orElse(null)));
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** A whole-population response: the raw body plus the {@value #VERIFIED_SOURCE_COUNT_HEADER} header (or null). */
    public record Snapshot(String body, Integer verifiedSourceCount) { }

    /**
     * Header in which the KF Agent reports that the response came from ONE full internal scan whose complete-scan guard
     * passed against this IIQ source count (Part 1 {@code KfAgentScanEvidence}).
     */
    public static final String VERIFIED_SOURCE_COUNT_HEADER = "X-KF-Verified-Source-Count";

    /**
     * Fetches the WHOLE population of {@code /kfagent/<resource>} in one request (no {@code start}/{@code limit}), so the
     * body comes from a single KF Agent scan — one consistent snapshot — together with its verified source count.
     */
    public Snapshot fetchSnapshot(String resource) {
        return fetchSnapshot(resource, null);
    }

    /**
     * Like {@link #fetchSnapshot(String)}, bounded to objects modified after {@code modifiedAfterIso} (an ISO-8601
     * instant) when it is not {@code null}: the KF Agent passes it to the plugin as a server-side filter. Supported only
     * by identities, accounts, applications, entitlements and roles.
     */
    public Snapshot fetchSnapshot(String resource, String modifiedAfterIso) {
        String url = config.kfAgentBaseUrl() + "/kfagent/" + resource
                + (modifiedAfterIso == null ? ""
                : "?modifiedAfter=" + java.net.URLEncoder.encode(modifiedAfterIso, StandardCharsets.UTF_8));
        HttpResponse<String> resp = get(url);
        return new Snapshot(resp.body(),
                sourceCount(resp.headers().firstValue(VERIFIED_SOURCE_COUNT_HEADER).orElse(null)));
    }

    private HttpResponse<String> get(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(config.httpTimeoutSeconds()))
                .header("Accept", "application/json")
                .GET();
        if (config.hasBasicAuth()) {
            String token = Base64.getEncoder().encodeToString(
                    (config.kfAgentUsername() + ":" + config.kfAgentPassword()).getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + token);
        }
        try {
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new KfAgentHttpException("GET " + url + " returned HTTP " + code + ": " + snippet(resp.body()));
            }
            return resp;
        } catch (KfAgentHttpException e) {
            throw e;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new KfAgentHttpException("GET " + url + " failed: " + e.getMessage(), e);
        }
    }

    /** Parses a {@value #SOURCE_COUNT_HEADER} value; absent, blank or non-integer → {@code null}. */
    public static Integer sourceCount(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return "<empty>";
        }
        String s = body.strip().replaceAll("\\s+", " ");
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    /** HTTP/transport failure talking to the KF Agent. */
    public static final class KfAgentHttpException extends RuntimeException {
        public KfAgentHttpException(String message) { super(message); }
        public KfAgentHttpException(String message, Throwable cause) { super(message, cause); }
    }
}

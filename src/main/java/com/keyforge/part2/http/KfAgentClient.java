package com.keyforge.part2.http;

import com.keyforge.part2.config.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Thin HTTP client for the Part 1 KF Agent REST endpoints. GETs one page of a resource
 * ({@code <base>/kfagent/<resource>?start=&limit=}) and returns the raw JSON body (a bare JSON array).
 * Read-only; no SailPoint/IIQ dependency. Optional HTTP Basic Auth is sent only when both
 * {@code KFAGENT_USERNAME} and {@code KFAGENT_PASSWORD} are configured (for an authenticating proxy in
 * front of the agent); the agent itself is unauthenticated.
 */
public final class KfAgentClient {

    private final AppConfig config;
    private final HttpClient http;

    public KfAgentClient(AppConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.httpTimeoutSeconds()))
                .build();
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

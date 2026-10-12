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
        String url = config.kfAgentBaseUrl() + "/kfagent/" + resource
                + "?start=" + Math.max(0, start) + "&limit=" + limit;
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
            return new Page(resp.body(), sourceCount(resp.headers().firstValue(SOURCE_COUNT_HEADER).orElse(null)));
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

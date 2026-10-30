package com.keyforge.part2.ingest;

import com.keyforge.part2.config.AppConfig;
import com.keyforge.part2.http.KfAgentClient;
import com.keyforge.part2.testsupport.RecordingJdbc;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives {@link EntityImportService} for Entitlement over REAL HTTP (an in-process mock KF Agent) into
 * recording JDBC stubs: start/limit paging until a short page, per-row failure isolation, and a clear error
 * for a non-array (error) response.
 */
class EntityImportServiceTest {

    private static final String[] KEYS = {"KFAGENT_BASE_URL", "KFAGENT_USERNAME", "KFAGENT_PASSWORD", "PG_HOST",
            "PG_DATABASE", "PG_USERNAME", "PG_PASSWORD", "PG_DEST_SCHEMA", "KFAGENT_PAGE_SIZE"};

    private HttpServer server;
    private final List<String> queries = new ArrayList<>();
    private volatile String errorBody;

    @BeforeEach
    void start() throws Exception {
        assumeTrue(System.getenv("KFAGENT_BASE_URL") == null, "env config would override the mock server");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/kfagent/entitlements", ex -> {
            String q = ex.getRequestURI().getQuery();
            queries.add(q);
            String body;
            int status = 200;
            if (errorBody != null) {
                body = errorBody;
                status = 500;
            } else if (q.equals("start=100&limit=1&application_name=App+Name")) {
                body = "[" + row("7f0001019eb91d3c819f01f0c4127ef5") + "]";
            } else if (q.equals("start=0&limit=1")) {
                body = "[" + row("7f0001019eb91d3c819f01f0c4127ef6") + "]";
            } else if (q.equals("modifiedAfter=2026-10-20T00:00:00Z")) {
                body = "[" + row("7f0001019eb91d3c819f01f0c4127ef7") + "]";
            } else if (q.startsWith("start=0&")) {
                body = "[" + row("7f0001019eb91d3c819f01f0c4127ef1") + "," + row(RecordingJdbc.FAIL_ON) + "]";
            } else if (q.startsWith("start=2&")) {
                body = "[" + row("7f0001019eb91d3c819f01f0c4127ef3") + "]"; // short page -> stop
            } else {
                body = "[]";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        System.setProperty("KFAGENT_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty("KFAGENT_USERNAME", "agent-user");
        System.setProperty("KFAGENT_PASSWORD", "agent-pass");
        System.setProperty("PG_HOST", "unused");
        System.setProperty("PG_DATABASE", "unused");
        System.setProperty("PG_USERNAME", "unused");
        System.setProperty("PG_PASSWORD", "unused");
        System.setProperty("KFAGENT_PAGE_SIZE", "2");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        for (String k : KEYS) {
            System.clearProperty(k);
        }
    }

    private static String row(String sourceId) {
        return "{\"source_id\":\"" + sourceId + "\",\"value\":\"v\",\"application_name\":\"App\",\"requestable\":true}";
    }

    private EntityImportService service(AppConfig cfg) {
        return new EntityImportService(new KfAgentClient(cfg),
                new EntityRepository(cfg.pgSchema(), EntitlementTable.SPEC), cfg.pageSize());
    }

    @Test
    void pagesUntilShortPageAndIsolatesRowFailures() throws Exception {
        AppConfig cfg = AppConfig.load();
        RecordingJdbc jdbc = new RecordingJdbc();
        ImportResult r = service(cfg).run(jdbc.connection(), "run-1");

        assertEquals(List.of("start=0&limit=2", "start=2&limit=2"), queries, "start/limit paging, stops on short page");
        assertEquals(2, r.pages());
        assertEquals(3, r.fetched());
        assertEquals(2, r.inserted());
        assertEquals(1, r.failed(), "bad row is reported, the rest still load");
        assertTrue(r.failures().get(0).startsWith(RecordingJdbc.FAIL_ON + ": "), "failure names the source_id");
        assertTrue(jdbc.ddl.get(0).startsWith("CREATE SCHEMA IF NOT EXISTS iiq_native_part2"));
        assertTrue(jdbc.ddl.get(1).contains(".kf_entitlement ("));
        assertEquals("iiq_native_part2.kf_entitlement", r.targetTable());
    }

    @Test
    void nonArrayResponseIsAClearSourceError() {
        errorBody = "{\"error\":\"boom\"}";
        AppConfig cfg = AppConfig.load();
        KfAgentClient.KfAgentHttpException e = assertThrows(KfAgentClient.KfAgentHttpException.class,
                () -> service(cfg).run(new RecordingJdbc().connection(), "run-1"));
        assertTrue(e.getMessage().contains("/kfagent/entitlements"), e.getMessage());
        assertTrue(e.getMessage().contains("500"), e.getMessage());
    }

    @Test
    void callerWindowRequestsExactlyThatOnePageWithEncodedFilterAndUsesTheExistingWriter() throws Exception {
        AppConfig cfg = AppConfig.load();
        RecordingJdbc jdbc = new RecordingJdbc();
        com.keyforge.part2.http.FetchOptions options = new com.keyforge.part2.http.FetchOptions(
                100, 1, null, java.util.Map.of("application_name", "App Name"));

        ImportResult result = service(cfg).run(jdbc.connection(), "run-page", options);

        assertEquals(List.of("start=100&limit=1&application_name=App+Name"), queries,
                "caller page is forwarded exactly; the full-import loop must not request later pages");
        assertEquals(1, result.pages());
        assertEquals(1, result.fetched());
        assertEquals(1, result.inserted());
        assertEquals("iiq_native_part2.kf_entitlement", result.targetTable());
    }

    @Test
    void callerFirstPageDoesNotContinueToLaterPages() throws Exception {
        AppConfig cfg = AppConfig.load();
        ImportResult result = service(cfg).run(new RecordingJdbc().connection(), "run-first-page",
                new com.keyforge.part2.http.FetchOptions(0, 1, null, java.util.Map.of()));
        assertEquals(List.of("start=0&limit=1"), queries);
        assertEquals(1, result.fetched());
    }

    @Test
    void callerModifiedAfterIsForwardedAsOneBoundedFetch() throws Exception {
        AppConfig cfg = AppConfig.load();
        ImportResult result = service(cfg).run(new RecordingJdbc().connection(), "run-filtered",
                new com.keyforge.part2.http.FetchOptions(null, null, "2026-10-20T00:00:00Z", java.util.Map.of()));
        // ':' is a legal query character, so the URI builder keeps it literal while preserving the timestamp.
        assertEquals(List.of("modifiedAfter=2026-10-20T00:00:00Z"), queries);
        assertEquals(1, result.fetched());
    }
}

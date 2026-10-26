package com.keyforge.part2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.config.ConfigException;
import com.keyforge.part2.incremental.ExtractionMode;
import com.keyforge.part2.ledger.ExtractionRun;
import com.keyforge.part2.ledger.RunContext;
import com.keyforge.part2.ledger.RunLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RestServer}: GET routes run the existing dispatch inside the existing run ledger, one at a time. */
class RestServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<ExtractionRun> recorded = Collections.synchronizedList(new ArrayList<>());
    private final List<String> dispatched = Collections.synchronizedList(new ArrayList<>());
    private final HttpClient http = HttpClient.newHttpClient();
    private RunLedger.Sink previousSink;
    private RestServer server;

    @BeforeEach
    void captureLedger() {
        previousSink = RunLedger.useSink(recorded::add);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
        RunLedger.useSink(previousSink);
    }

    private static final String USER = "spadmin";
    private static final String PASSWORD = "Agent-Pa55:word"; // a ':' in the password must still work
    private static final String VALID = basic(USER, PASSWORD);

    private void start(RestServer.Dispatcher dispatcher) throws Exception {
        server = new RestServer(new RestServer.Settings("127.0.0.1", 0, USER, PASSWORD), dispatcher);
        server.start();
    }

    private void startFetch(RestServer.FetchDispatcher dispatcher) throws Exception {
        server = new RestServer(new RestServer.Settings("127.0.0.1", 0, USER, PASSWORD), dispatcher);
        server.start();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + pathAndQuery));
    }

    /** GET with the valid credentials. */
    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return getWith(pathAndQuery, VALID);
    }

    /** GET with this Authorization header ({@code null} = none). */
    private HttpResponse<String> getWith(String pathAndQuery, String authorization) throws Exception {
        HttpRequest.Builder b = request(pathAndQuery);
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return MAPPER.readTree(r.body());
    }

    @Test
    void theRoutesAreExactlyTheCliCommands() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = System.out;
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            assertEquals(2, RunLedger.run("", () -> Main.dispatch(""))); // usage only, nothing runs
        } finally {
            System.setOut(out);
        }
        Set<String> usage = new HashSet<>();
        Matcher m = Pattern.compile("(?m)^  ((?:import|reconcile|derive)-[a-z-]+)").matcher(buf.toString(StandardCharsets.UTF_8));
        while (m.find()) {
            usage.add(m.group(1));
        }
        assertEquals(usage, new HashSet<>(RestServer.COMMANDS));
        assertEquals(43, RestServer.COMMANDS.size());
        assertFalse(RestServer.COMMANDS.contains(Main.START_REST));
        assertTrue(recorded.isEmpty());
    }

    @Test
    void aGetRunsTheDispatcherInsideTheLedgerOnTheSameThreadAndReturnsThatRun() throws Exception {
        AtomicReference<String> runIdSeenByDispatch = new AtomicReference<>();
        start((command, mode) -> {
            dispatched.add(command + "|" + mode);
            runIdSeenByDispatch.set(RunLedger.currentRunId()); // thread-local: non-null only on the ledger's thread
            RunLedger.record(new RunContext.EntityCount("kf_identity", 3, 2, 1, 0, 0, null, null, null));
            return 0;
        });

        HttpResponse<String> r = get("/kfagent-ingest/import-identity");
        JsonNode body = json(r);

        assertEquals(200, r.statusCode());
        assertEquals(List.of("import-identity|FULL"), dispatched);
        assertNotNull(runIdSeenByDispatch.get());
        assertEquals(1, recorded.size());
        ExtractionRun run = recorded.get(0);
        assertEquals("import-identity", run.command());
        assertEquals("kfagent-rest", run.sourceInterface());
        assertEquals(run.extractionRunId(), runIdSeenByDispatch.get());

        assertEquals("import-identity", body.get("command").asText());
        assertEquals("full", body.get("mode").asText());
        assertEquals(0, body.get("exit_code").asInt());
        assertEquals("SUCCESS", body.get("status").asText());
        assertTrue(body.get("success").asBoolean());
        assertEquals(run.extractionRunId(), body.get("run_id").asText());
        assertEquals(3, body.get("extracted").asInt());
        assertEquals(2, body.get("inserted").asInt());
        assertEquals(1, body.get("updated").asInt());
        assertEquals(0, body.get("failed").asInt());
        assertEquals("kf_identity", body.get("entity_counts").get(0).get("entity").asText());
        assertEquals("SUCCESS", r.headers().firstValue("X-KF-Run-Status").orElse(null));
    }

    @Test
    void modeIncrementalMapsToTheExistingExtractionModeAndLedgerCommand() throws Exception {
        start((command, mode) -> {
            dispatched.add(command + "|" + mode);
            return 0;
        });

        assertEquals(200, get("/kfagent-ingest/import-entitlement?mode=incremental").statusCode());
        assertEquals(200, get("/kfagent-ingest/import-entitlement?mode=full").statusCode());
        assertEquals(200, get("/kfagent-ingest/import-entitlement").statusCode());

        assertEquals(List.of("import-entitlement|INCREMENTAL", "import-entitlement|FULL", "import-entitlement|FULL"),
                dispatched);
        assertEquals("import-entitlement --incremental", recorded.get(0).command());
        assertEquals("import-entitlement", recorded.get(1).command());
    }

    @Test
    void badQueryParametersAreRejectedWithoutRunning() throws Exception {
        start((command, mode) -> {
            dispatched.add(command);
            return 0;
        });

        assertEquals(400, get("/kfagent-ingest/import-identity?mode=delta-ish").statusCode());
        assertEquals(400, get("/kfagent-ingest/import-identity?limit=10").statusCode());
        assertEquals(400, get("/kfagent-ingest/import-identity?mode=full&mode=incremental").statusCode());
        assertEquals(400, get("/kfagent-ingest/import-identity?unknown_filter=x").statusCode());
        assertEquals(400, get("/kfagent-ingest/import-certification?modifiedAfter=2026-10-20T00%3A00%3A00Z").statusCode());
        assertEquals(400, get("/kfagent-ingest/import-entitlement-assignment?start=0&limit=2").statusCode());
        assertTrue(dispatched.isEmpty());
        assertTrue(recorded.isEmpty());
    }

    @Test
    void callerPageAndIdentityFiltersReachDispatcherAndAreReported() throws Exception {
        AtomicReference<com.keyforge.part2.http.FetchOptions> received = new AtomicReference<>();
        startFetch((command, mode, options) -> {
            dispatched.add(command + "|" + mode);
            received.set(options);
            RunLedger.record(new RunContext.EntityCount("kf_identity", 2, 1, 1, 0, 0, null, null, null));
            return 0;
        });

        HttpResponse<String> response = get("/kfagent-ingest/import-identity?start=100&limit=100&name=spadmin&inactive=false");
        JsonNode body = json(response);

        assertEquals(200, response.statusCode());
        assertEquals("import-identity|FULL", dispatched.get(0));
        assertEquals(100, received.get().start());
        assertEquals(100, received.get().limit());
        assertEquals("spadmin", received.get().filters().get("name"));
        assertEquals("false", received.get().filters().get("inactive"));
        assertEquals("identities", body.get("fetch_options").get("resource").asText());
        assertEquals(200, body.get("next_offset").asInt());
        assertEquals(2, body.get("records_received").asInt());
    }

    @Test
    void managedIncrementalRemainsSeparateAndRejectsCallerTimestamp() throws Exception {
        start((command, mode) -> { dispatched.add(command + "|" + mode); return 0; });
        assertEquals(200, get("/kfagent-ingest/import-identity?mode=incremental").statusCode());
        assertEquals(400, get("/kfagent-ingest/import-identity?mode=incremental&start=0&limit=10").statusCode());
        assertEquals(List.of("import-identity|INCREMENTAL"), dispatched);
    }

    @Test
    void callerModifiedAfterStaysFullModeAndDoesNotBecomeManagedIncremental() throws Exception {
        AtomicReference<com.keyforge.part2.http.FetchOptions> received = new AtomicReference<>();
        AtomicReference<ExtractionMode> modeSeen = new AtomicReference<>();
        startFetch((command, mode, options) -> { received.set(options); modeSeen.set(mode); return 0; });
        assertEquals(200, get("/kfagent-ingest/import-identity?modifiedAfter=2026-10-20T00%3A00%3A00Z").statusCode());
        assertEquals("2026-10-20T00:00:00Z", received.get().modifiedAfter());
        assertEquals(ExtractionMode.FULL, modeSeen.get());
    }

    @Test
    void unknownRoutesAndMethodsAreRejectedWithoutRunning() throws Exception {
        start((command, mode) -> {
            dispatched.add(command);
            return 0;
        });

        assertEquals(404, get("/kfagent-ingest/import-nope").statusCode());
        assertEquals(404, get("/kfagent-ingest/start-rest").statusCode());
        assertEquals(404, get("/kfagent-ingest/import-identity/../../etc").statusCode());
        assertEquals(404, get("/other/import-identity").statusCode());
        HttpResponse<String> post = http.send(request("/kfagent-ingest/import-identity").header("Authorization", VALID)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
        HttpResponse<String> list = get("/kfagent-ingest");
        assertEquals(200, list.statusCode());
        assertEquals(RestServer.COMMANDS.size(), json(list).get("commands").size());

        assertTrue(dispatched.isEmpty());
        assertTrue(recorded.isEmpty());
    }

    @Test
    void partialAndIncompleteRunsAreNotReportedAsSuccess() throws Exception {
        start((command, mode) -> {
            if (command.equals("import-account")) {
                RunLedger.record(new RunContext.EntityCount("kf_account", 10, 8, 0, 2, 0, null, null, null));
                return 6;
            }
            RunLedger.record(new RunContext.EntityCount("kf_syslog_event", 5, 5, 0, 0, 0, null, null, null));
            RunLedger.error("incomplete scan: shrink");
            return 7;
        });

        HttpResponse<String> partial = get("/kfagent-ingest/import-account");
        JsonNode p = json(partial);
        assertEquals(200, partial.statusCode());
        assertEquals(6, p.get("exit_code").asInt());
        assertEquals("COMPLETED_WITH_FAILURES", p.get("status").asText());
        assertFalse(p.get("success").asBoolean());
        assertEquals(2, p.get("failed").asInt());
        assertTrue(p.get("message").asText().startsWith("NOT fully successful"));
        assertEquals("COMPLETED_WITH_FAILURES", partial.headers().firstValue("X-KF-Run-Status").orElse(null));

        HttpResponse<String> incomplete = get("/kfagent-ingest/import-syslog-event");
        JsonNode i = json(incomplete);
        assertEquals(200, incomplete.statusCode());
        assertEquals(7, i.get("exit_code").asInt());
        assertEquals("INCOMPLETE", i.get("status").asText());
        assertFalse(i.get("success").asBoolean());
        assertEquals("incomplete scan: shrink", i.get("error").asText());
        assertTrue(i.get("message").asText().startsWith("NOT complete"));

        assertEquals("COMPLETED_WITH_FAILURES", recorded.get(0).status());
        assertEquals("INCOMPLETE", recorded.get(1).status());
    }

    @Test
    void failuresUseTheRunsOwnErrorAndAMatchingHttpStatus() throws Exception {
        start((command, mode) -> {
            switch (command) {
                case "import-role" -> {
                    RunLedger.error("KF Agent HTTP/source error: GET http://agent/kfagent/roles returned HTTP 503");
                    return 4;
                }
                case "import-policy" -> {
                    RunLedger.error("PostgreSQL error: connection refused");
                    return 5;
                }
                case "import-workgroup" -> {
                    RunLedger.error("configuration error: Missing required configuration: PG_HOST");
                    return 3;
                }
                default -> throw new IllegalStateException("boom");
            }
        });

        HttpResponse<String> source = get("/kfagent-ingest/import-role");
        assertEquals(502, source.statusCode());
        assertEquals("FAILED", json(source).get("status").asText());
        assertEquals(4, json(source).get("exit_code").asInt());
        assertTrue(json(source).get("error").asText().contains("HTTP 503"));

        assertEquals(500, get("/kfagent-ingest/import-policy").statusCode());
        assertEquals(500, get("/kfagent-ingest/import-workgroup").statusCode());

        HttpResponse<String> unexpected = get("/kfagent-ingest/import-task-result");
        JsonNode u = json(unexpected);
        assertEquals(500, unexpected.statusCode());
        assertEquals(1, u.get("exit_code").asInt());
        assertEquals("FAILED", u.get("status").asText());
        assertEquals("unexpected error: boom", u.get("error").asText());
        assertNotNull(u.get("run_id").textValue());

        assertEquals(4, recorded.size()); // every one is a recorded (FAILED) run, as on the CLI
        assertTrue(recorded.stream().allMatch(run -> "FAILED".equals(run.status())));
    }

    @Test
    void aDispatcherUsageRejectionIsA400WithNoRunRecorded() throws Exception {
        start(Main::dispatch); // the REAL dispatcher: identity-role has no incremental mode
        HttpResponse<String> r = get("/kfagent-ingest/import-identity-role?mode=incremental");
        JsonNode body = json(r);

        assertEquals(400, r.statusCode());
        assertEquals(2, body.get("exit_code").asInt());
        assertEquals("REJECTED", body.get("status").asText());
        assertFalse(body.get("success").asBoolean());
        assertTrue(body.get("run_id").isNull());
        assertTrue(recorded.isEmpty());
    }

    @Test
    void onlyOneCommandRunsAtATime() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        start((command, mode) -> {
            dispatched.add(command);
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        });

        CompletableFuture<HttpResponse<String>> first = http.sendAsync(request("/kfagent-ingest/import-identity")
                .header("Authorization", VALID).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(started.await(10, TimeUnit.SECONDS));

        HttpResponse<String> second = get("/kfagent-ingest/import-account");
        assertEquals(409, second.statusCode());

        release.countDown();
        assertEquals(200, first.get(10, TimeUnit.SECONDS).statusCode());
        assertEquals(List.of("import-identity"), dispatched);
        assertEquals(200, get("/kfagent-ingest/import-account").statusCode()); // free again afterwards
    }

    @Test
    void largeRunsAreReportedInFullWithoutAnyWrapperLimit() throws Exception {
        start((command, mode) -> {
            try {
                Thread.sleep(1500); // longer than any trivial handler would take; no wrapper deadline applies
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            RunLedger.record(new RunContext.EntityCount("kf_syslog_event", 2_500_000, 2_500_000, 0, 0, 0,
                    null, null, null));
            return 0;
        });

        JsonNode body = json(get("/kfagent-ingest/import-syslog-event"));
        assertEquals("SUCCESS", body.get("status").asText());
        assertEquals(2_500_000, body.get("extracted").asInt());
        assertTrue(body.get("duration_ms").asLong() >= 1500);
    }

    @Test
    void validBasicCredentialsAreAccepted() throws Exception {
        start((command, mode) -> {
            dispatched.add(command);
            return 0;
        });

        assertEquals(200, getWith("/kfagent-ingest/import-identity", VALID).statusCode());
        assertEquals(200, getWith("/kfagent-ingest/import-identity", // the scheme name is case-insensitive
                "basic " + VALID.substring("Basic ".length())).statusCode());
        assertEquals(List.of("import-identity", "import-identity"), dispatched);
    }

    @Test
    void missingOrWrongCredentialsGet401OnEveryEndpointAndRunNothing() throws Exception {
        start((command, mode) -> {
            dispatched.add(command);
            return 0;
        });

        List<String> rejected = new ArrayList<>();
        rejected.add(null);                                     // no Authorization header
        rejected.add(basic(USER, "wrong"));                     // wrong password
        rejected.add(basic("someone-else", PASSWORD));          // wrong username
        rejected.add(basic(USER, ""));                          // empty password
        rejected.add(basic(USER, PASSWORD + "x"));              // longer password
        rejected.add("Basic not-base64!!");                     // malformed
        rejected.add("Basic " + Base64.getEncoder().encodeToString(USER.getBytes(StandardCharsets.UTF_8))); // no ':'
        rejected.add("Bearer " + PASSWORD);                     // another scheme
        for (String auth : rejected) {
            for (String path : List.of("/kfagent-ingest/import-identity", "/kfagent-ingest/import-entitlement"
                    + "?mode=incremental", "/kfagent-ingest", "/kfagent-ingest/nope", "/other")) {
                HttpResponse<String> r = getWith(path, auth);
                assertEquals(401, r.statusCode(), auth + " " + path);
                assertTrue(r.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic "));
            }
        }
        HttpResponse<String> post = http.send(request("/kfagent-ingest/import-identity")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, post.statusCode()); // authentication comes before any other check
        assertTrue(dispatched.isEmpty());
        assertTrue(recorded.isEmpty());
    }

    @Test
    void thePasswordIsNeverLoggedOrReturned() throws Exception {
        start((command, mode) -> 0);
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        PrintStream out = System.out;
        PrintStream err = System.err;
        PrintStream capture = new PrintStream(logs, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        List<String> bodies = new ArrayList<>();
        try {
            bodies.add(get("/kfagent-ingest/import-identity").body());
            bodies.add(get("/kfagent-ingest").body());
            bodies.add(get("/kfagent-ingest/nope").body());
            bodies.add(getWith("/kfagent-ingest/import-identity", basic(USER, "wrong")).body());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        String all = logs.toString(StandardCharsets.UTF_8) + String.join("", bodies);
        assertFalse(all.contains(PASSWORD));
        assertFalse(all.contains(VALID.substring("Basic ".length())));
        assertFalse(new RestServer.Settings("127.0.0.1", 8200, USER, PASSWORD).toString().contains(PASSWORD));
    }

    @Test
    void restModeNeedsTheKfAgentCredentialsAndKeepsTheLoopbackDefault() {
        assertEquals("127.0.0.1", RestServer.DEFAULT_HOST);
        assertEquals(8200, RestServer.DEFAULT_PORT);

        new RestServer.Settings("127.0.0.1", 8200, USER, PASSWORD);
        ConfigException noUser = assertThrows(ConfigException.class,
                () -> new RestServer.Settings("127.0.0.1", 8200, null, PASSWORD));
        assertTrue(noUser.getMessage().contains("KFAGENT_USERNAME"));
        assertThrows(ConfigException.class, () -> new RestServer.Settings("127.0.0.1", 8200, USER, null));
        assertThrows(ConfigException.class, () -> new RestServer.Settings("127.0.0.1", 70000, USER, PASSWORD));
    }

    @Test
    void exitCodesMapToHttpStatuses() {
        assertEquals(200, RestServer.httpStatus(0));
        assertEquals(200, RestServer.httpStatus(6));
        assertEquals(200, RestServer.httpStatus(7));
        assertEquals(400, RestServer.httpStatus(2));
        assertEquals(500, RestServer.httpStatus(3));
        assertEquals(502, RestServer.httpStatus(4));
        assertEquals(500, RestServer.httpStatus(5));
        assertEquals(500, RestServer.httpStatus(1));
    }

    @Test
    void theCliStillTreatsStartRestAsNoImportAndUnknownCommandsAsUsageErrors() {
        assertEquals(2, RunLedger.run("start-rest", () -> Main.dispatch("start-rest")));
        assertEquals(2, RunLedger.run("import-nope", () -> Main.dispatch("import-nope")));
        assertTrue(recorded.isEmpty());
        assertEquals(ExtractionMode.INCREMENTAL, ExtractionMode.fromArgs(new String[] {"--incremental"}));
    }
}

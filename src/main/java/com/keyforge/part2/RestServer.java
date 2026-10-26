package com.keyforge.part2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.config.ConfigException;
import com.keyforge.part2.incremental.ExtractionMode;
import com.keyforge.part2.http.FetchOptions;
import com.keyforge.part2.ledger.ExtractionRun;
import com.keyforge.part2.ledger.RunLedger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code start-rest}: a minimal HTTP trigger for the existing CLI commands. {@code GET /kfagent-ingest/<command>}
 * (optionally {@code ?mode=incremental}) runs exactly what {@code java -jar kfagent-ingest.jar <command>
 * [--incremental]} runs — {@link RunLedger#run} around {@link Main#dispatch(String, ExtractionMode)}, synchronously on
 * the request thread — and answers with that run's own ledger data. The server does no extraction, mapping, paging or
 * database work of its own, has no time or record limit, and runs ONE command at a time (others get 409).
 *
 * <p>Configuration (environment, {@code -D} fallback, like {@code AppConfig}): {@code REST_HOST} (default 127.0.0.1),
 * {@code REST_PORT} (default 8200). Every request needs HTTP Basic Auth with the existing {@code KFAGENT_USERNAME} /
 * {@code KFAGENT_PASSWORD} (the same values Part 2 sends to the KF Agent; both are required to start). The command
 * itself uses the usual KF Agent / PostgreSQL settings.
 */
final class RestServer {

    static final String BASE_PATH = "/kfagent-ingest";
    static final String DEFAULT_HOST = "127.0.0.1";
    static final int DEFAULT_PORT = 8200;

    /** Every command {@link Main#dispatch(String, ExtractionMode)} runs (same names as the CLI). */
    static final List<String> COMMANDS = List.of(
            "import-identity", "import-entitlement", "import-application", "import-account", "import-workgroup",
            "import-role", "import-identity-role", "import-identity-entitlement", "import-account-entitlement",
            "import-role-entitlement", "import-role-hierarchy", "import-identity-request",
            "import-identity-request-item", "import-identity-request-approval", "import-policy",
            "import-provisioning-item", "import-provisioning-transaction", "import-work-item",
            "import-workflow-definition", "import-certification", "import-certification-entity",
            "import-certification-item", "import-certification-archive", "import-policy-constraint",
            "import-policy-violation", "import-entitlement-assignment", "import-workgroup-member",
            "import-entitlement-certification", "import-entitlement-certification-status", "import-workgroup-members",
            "import-audit-event", "import-task-result", "import-task-schedule", "import-workitem-archive",
            "import-group-definition", "import-access-history", "import-access-history-identity-event",
            "import-access-history-certification", "import-access-history-role-event", "import-syslog-event",
            "reconcile-native-workitems", "derive-native-events", "derive-native-event-links");

    /** The command runner ({@link Main#dispatch(String, ExtractionMode)} in production; replaceable in tests). */
    @FunctionalInterface
    interface Dispatcher {
        int dispatch(String command, ExtractionMode mode);
    }

    @FunctionalInterface
    interface FetchDispatcher {
        int dispatch(String command, ExtractionMode mode, FetchOptions options);
    }

    /** Bind address, port (0 = any free port) and the Basic Auth credentials every request must present. */
    record Settings(String host, int port, String username, String password) {

        Settings {
            if (port < 0 || port > 65535) {
                throw new ConfigException("REST_PORT must be between 1 and 65535 but was: " + port);
            }
            if (username == null || password == null) {
                throw new ConfigException("REST mode requires KFAGENT_USERNAME and KFAGENT_PASSWORD: every request "
                        + "must authenticate with them (HTTP Basic Auth).");
            }
        }

        /** Never prints the password. */
        @Override
        public String toString() {
            return "Settings[host=" + host + ", port=" + port + ", username=" + username + ", password=****]";
        }

        static Settings fromEnvironment() {
            String host = resolve("REST_HOST");
            String port = resolve("REST_PORT");
            int p = DEFAULT_PORT;
            if (port != null) {
                try {
                    p = Integer.parseInt(port.trim());
                } catch (NumberFormatException e) {
                    throw new ConfigException("REST_PORT must be an integer but was: " + port);
                }
                if (p == 0) {
                    throw new ConfigException("REST_PORT must be between 1 and 65535 but was: 0");
                }
            }
            // resolved exactly as AppConfig resolves them for the outbound KF Agent calls
            return new Settings(host == null ? DEFAULT_HOST : host.trim(), p, resolve("KFAGENT_USERNAME"),
                    resolve("KFAGENT_PASSWORD"));
        }

        private static String resolve(String key) {
            String v = System.getenv(key);
            if (v == null || v.trim().isEmpty()) {
                v = System.getProperty(key);
            }
            return (v == null || v.trim().isEmpty()) ? null : v;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Settings settings;
    private final Dispatcher dispatcher;
    private final FetchDispatcher fetchDispatcher;
    private final AtomicBoolean busy = new AtomicBoolean();
    private HttpServer server;
    private ExecutorService executor;

    RestServer(Settings settings, Dispatcher dispatcher) {
        this.settings = settings;
        this.dispatcher = dispatcher;
        this.fetchDispatcher = (command, mode, options) -> options.isEmpty()
                ? dispatcher.dispatch(command, mode) : 2;
    }

    RestServer(Settings settings, FetchDispatcher dispatcher) {
        this.settings = settings;
        this.dispatcher = null;
        this.fetchDispatcher = dispatcher;
    }

    /** {@code start-rest}: starts the server and returns 0 (it keeps running until the JVM is stopped), or 3 / 1. */
    static int startFromEnvironment() {
        try {
            RestServer rest = new RestServer(Settings.fromEnvironment(),
                    (command, mode, options) -> Main.dispatch(command, mode, options));
            rest.start();
            Runtime.getRuntime().addShutdownHook(new Thread(rest::stop, "rest-shutdown"));
            System.out.println("[rest] listening on http://" + rest.settings.host() + ":" + rest.port() + BASE_PATH
                    + "/<command>  (HTTP Basic Auth with KFAGENT_USERNAME / KFAGENT_PASSWORD; one command at a "
                    + "time; Ctrl+C to stop)");
            return 0;
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            return 3;
        } catch (IOException e) {
            System.err.println("Could not start the REST server: " + e.getMessage());
            return 1;
        }
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(settings.host(), settings.port()), 0);
        // a few handler threads, so a request arriving during an import is answered 409 instead of waiting
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if (!authorized(ex.getRequestHeaders().getFirst("Authorization"))) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"kfagent-ingest\", charset=\"UTF-8\"");
                send(ex, 401, error("missing or invalid credentials (HTTP Basic Auth)"));
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                send(ex, 405, error("method not allowed: use GET"));
                return;
            }
            if (path.equals(BASE_PATH) || path.equals(BASE_PATH + "/")) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("usage", "GET " + BASE_PATH + "/<command>[?mode=full|incremental&start=<offset>&limit=<count>&<supported-filter>=<value>&modifiedAfter=<instant>]");
                body.put("commands", COMMANDS);
                send(ex, 200, body);
                return;
            }
            String command = path.startsWith(BASE_PATH + "/") ? path.substring(BASE_PATH.length() + 1) : null;
            if (command == null || !COMMANDS.contains(command)) {
                send(ex, 404, error("unknown route " + path + "; see GET " + BASE_PATH + " for the commands"));
                return;
            }
            RequestOptions request;
            try {
                request = requestFrom(ex.getRequestURI().getRawQuery(), command);
            } catch (IllegalArgumentException e) {
                send(ex, 400, error(e.getMessage()));
                return;
            }
            if (!busy.compareAndSet(false, true)) {
                send(ex, 409, error("another command is already running; nothing was started"));
                return;
            }
            Map<String, Object> result;
            try {
                System.out.println("[rest] GET " + path + " -> " + command
                        + (request.mode().isIncremental() ? " --incremental" : "")
                        + (request.fetchOptions().isEmpty() ? "" : " [caller fetch options]"));
                result = execute(command, request.mode(), request.fetchOptions());
            } finally {
                busy.set(false);
            }
            System.out.println("[rest] " + command + " finished: exit " + result.get("exit_code") + ", "
                    + result.get("status"));
            ex.getResponseHeaders().add("X-KF-Run-Status", String.valueOf(result.get("status")));
            send(ex, httpStatus((Integer) result.get("exit_code")), result);
        } catch (IOException e) {
            // the caller went away; the command (if any) already ran to completion and its run is recorded
            System.err.println("[rest] could not send the response: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    /** Runs the command exactly as {@link Main#main} does, on this thread, and builds the response from its run. */
    Map<String, Object> execute(String command, ExtractionMode mode) {
        return execute(command, mode, FetchOptions.none());
    }

    Map<String, Object> execute(String command, ExtractionMode mode, FetchOptions options) {
        String ledgerCommand = mode.isIncremental() ? command + " --incremental" : command;
        String[] runId = new String[1];
        ExtractionRun[] run = new ExtractionRun[1];
        int exitCode;
        String unexpected = null;
        try {
            exitCode = RunLedger.run(ledgerCommand, () -> {
                runId[0] = RunLedger.currentRunId();
                int code = fetchDispatcher.dispatch(command, mode, options);
                run[0] = RunLedger.snapshot(code); // null when the dispatcher discarded the run (usage error)
                return code;
            });
        } catch (RuntimeException e) {
            exitCode = 1;
            unexpected = "unexpected error: " + e.getMessage();
            System.err.println("[rest] " + command + " " + unexpected);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", command);
        body.put("mode", mode.isIncremental() ? "incremental" : "full");
        if (!options.isEmpty()) {
            Map<String, Object> requested = new LinkedHashMap<>();
            requested.put("resource", Main.fetchResource(command));
            requested.put("start", options.start());
            requested.put("limit", options.limit());
            requested.put("modifiedAfter", options.modifiedAfter());
            requested.put("filters", options.filters());
            body.put("fetch_options", requested);
            if (options.hasWindow()) {
                body.put("next_offset", options.start() + options.limit());
            }
        }
        body.put("exit_code", exitCode);
        ExtractionRun r = run[0];
        String status = r != null ? r.status() : exitCode == 2 ? "REJECTED" : "FAILED";
        body.put("status", status);
        body.put("success", "SUCCESS".equals(status));
        body.put("message", messageFor(status, r));
        body.put("run_id", r != null ? r.extractionRunId() : exitCode == 2 ? null : runId[0]);
        body.put("error", r != null ? r.errorMessage() : unexpected);
        if (r != null) {
            body.put("started_at", r.startedAt().toString());
            body.put("ended_at", r.endedAt().toString());
            body.put("duration_ms", r.durationMs());
            body.put("window_start", r.windowStart() == null ? null : r.windowStart().toString());
            body.put("window_end", r.windowEnd() == null ? null : r.windowEnd().toString());
            body.put("extracted", r.extracted());
            if (!options.isEmpty()) body.put("records_received", r.extracted());
            body.put("inserted", r.inserted());
            body.put("updated", r.updated());
            body.put("failed", r.failed());
            body.put("entity_counts", entityCounts(r.entityCountsJson()));
        }
        return body;
    }

    private static String messageFor(String status, ExtractionRun r) {
        return switch (status) {
            case "SUCCESS" -> "completed successfully";
            case "COMPLETED_WITH_FAILURES" -> "NOT fully successful: the command completed, but "
                    + (r == null || r.failed() == null ? "some" : r.failed()) + " row(s) failed to load; see error "
                    + "and entity_counts";
            case "INCOMPLETE" -> "NOT complete: rows were imported, but the scan / sweep completeness could not be "
                    + "confirmed (exit 7); see error";
            case "REJECTED" -> "rejected by the command dispatcher before any import started (e.g. "
                    + "mode=incremental is not supported for this command); nothing was run or recorded — see the "
                    + "server log";
            default -> "the command failed; see error";
        };
    }

    /** 0, 6 and 7 ran (the JSON status says whether fully); 2 = rejected; 4 = KF Agent; others = server-side. */
    static int httpStatus(int exitCode) {
        return switch (exitCode) {
            case 0, 6, 7 -> 200;
            case 2 -> 400;
            case 4 -> 502;
            default -> 500;
        };
    }

    /** {@code mode=full|incremental} (absent = full, as on the CLI); any other parameter is rejected. */
    static ExtractionMode modeFrom(String rawQuery) {
        ExtractionMode mode = ExtractionMode.FULL;
        if (rawQuery == null || rawQuery.isEmpty()) {
            return mode;
        }
        boolean seen = false;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8).trim();
            if (!"mode".equals(key)) {
                throw new IllegalArgumentException("unsupported query parameter '" + key + "' (only mode is accepted)");
            }
            if (seen) {
                throw new IllegalArgumentException("mode given more than once");
            }
            seen = true;
            if (value.equalsIgnoreCase("incremental")) {
                mode = ExtractionMode.INCREMENTAL;
            } else if (!value.equalsIgnoreCase("full")) {
                throw new IllegalArgumentException("mode must be 'full' or 'incremental' but was '" + value + "'");
            }
        }
        return mode;
    }

    private record RequestOptions(ExtractionMode mode, FetchOptions fetchOptions) { }

    /** Parse only parameters explicitly supported by the selected Part 1 resource. */
    static RequestOptions requestFrom(String rawQuery, String command) {
        ExtractionMode mode = ExtractionMode.FULL;
        Integer start = null;
        Integer limit = null;
        String modifiedAfter = null;
        Map<String, String> filters = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> filterFields = Main.fetchFilterFields(command);
        boolean supportsModifiedAfter = Main.supportsCallerModifiedAfter(command);
        boolean supportsFetch = Main.supportsCallerFetchOptions(command);
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (!seen.add(key)) throw new IllegalArgumentException("query parameter '" + key + "' given more than once");
                switch (key) {
                    case "mode" -> {
                        if (value.equalsIgnoreCase("incremental")) mode = ExtractionMode.INCREMENTAL;
                        else if (value.equalsIgnoreCase("full")) mode = ExtractionMode.FULL;
                        else throw new IllegalArgumentException("mode must be 'full' or 'incremental' but was '" + value + "'");
                    }
                    case "start" -> {
                        if (!supportsFetch) throw new IllegalArgumentException("caller fetch options are not supported for '" + command + "'");
                        start = parseNonNegativeInt("start", value);
                    }
                    case "limit" -> {
                        if (!supportsFetch) throw new IllegalArgumentException("caller fetch options are not supported for '" + command + "'");
                        limit = parsePositiveInt("limit", value);
                        int max = maxFetchLimit();
                        if (limit > max) throw new IllegalArgumentException("limit exceeds REST_MAX_FETCH_LIMIT (" + max + ")");
                    }
                    case "modifiedAfter" -> {
                        if (!supportsFetch) throw new IllegalArgumentException("caller fetch options are not supported for '" + command + "'");
                        if (!supportsModifiedAfter) throw new IllegalArgumentException("'modifiedAfter' is not supported by Part 1 resource for '" + command + "'");
                        try { java.time.Instant.parse(value); }
                        catch (RuntimeException e) { throw new IllegalArgumentException("modifiedAfter must be an ISO-8601 instant"); }
                        modifiedAfter = value;
                    }
                    default -> {
                        if (!supportsFetch) throw new IllegalArgumentException("caller fetch options are not supported for '" + command + "'");
                        if (!filterFields.contains(key)) throw new IllegalArgumentException("unsupported query parameter/filter '" + key + "' for '" + command + "'");
                        filters.put(key, value);
                    }
                }
            }
        }
        if ((start == null) != (limit == null)) throw new IllegalArgumentException("start and limit must be supplied together");
        if (start != null && start > Integer.MAX_VALUE - limit) throw new IllegalArgumentException("start + limit overflows the supported offset range");
        FetchOptions fetch = new FetchOptions(start, limit, modifiedAfter, filters);
        if (mode.isIncremental() && !fetch.isEmpty()) throw new IllegalArgumentException("mode=incremental cannot be combined with caller fetch options; omit them to use the managed watermark");
        return new RequestOptions(mode, fetch);
    }

    private static int maxFetchLimit() {
        String raw = System.getenv("REST_MAX_FETCH_LIMIT");
        if (raw == null || raw.isBlank()) raw = System.getProperty("REST_MAX_FETCH_LIMIT", "10000");
        try {
            int n = Integer.parseInt(raw.trim());
            if (n <= 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("REST_MAX_FETCH_LIMIT must be a positive integer");
        }
    }

    private static int parseNonNegativeInt(String name, String raw) {
        try { int n = Integer.parseInt(raw); if (n < 0) throw new NumberFormatException(); return n; }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " must be a non-negative 32-bit integer"); }
    }

    private static int parsePositiveInt(String name, String raw) {
        try { int n = Integer.parseInt(raw); if (n <= 0) throw new NumberFormatException(); return n; }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " must be a positive 32-bit integer"); }
    }

    /** {@code Authorization: Basic base64(user:password)} matching the configured credentials (constant-time). */
    private boolean authorized(String header) {
        if (header == null || header.length() < 6 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        boolean user = MessageDigest.isEqual(decoded.substring(0, colon).getBytes(StandardCharsets.UTF_8),
                settings.username().getBytes(StandardCharsets.UTF_8));
        boolean pass = MessageDigest.isEqual(decoded.substring(colon + 1).getBytes(StandardCharsets.UTF_8),
                settings.password().getBytes(StandardCharsets.UTF_8));
        return user & pass;
    }

    private static Object entityCounts(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}

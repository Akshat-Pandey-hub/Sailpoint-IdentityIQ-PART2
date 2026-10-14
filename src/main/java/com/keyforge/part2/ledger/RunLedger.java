package com.keyforge.part2.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.config.AppConfig;
import com.keyforge.part2.db.PostgresConnection;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Thread-local extraction-run ledger for Part 2 import commands, reproducing Part 1's {@code iiq.runledger.RunLedger}.
 * {@link #run} wraps one command: it opens a run (fresh UUID), runs the command, and records ONE
 * {@code kf_extraction_run} row, also when the command fails (status FAILED with the error). The row is written on a
 * fresh connection after the command finished, best-effort: a ledger problem is reported on stderr but never changes
 * the command's exit code. {@link #currentRunId()} is the {@code extraction_run_id} the command stamps on its rows, so
 * ledger rows join to the data they produced. Reporting methods are no-ops outside a run.
 *
 * <p>Ownership: this ledger is Part 2 state (a record of Part 2's own runs); it is not fetched from the KF Agent.
 */
public final class RunLedger {

    /** {@code source_interface} of a Part 2 import: the KF Agent REST API. */
    public static final String SOURCE_INTERFACE = "kfagent-rest";

    /** {@code source_interface} of a command computed from already-loaded tables (Part 1's value for reconcile-*). */
    public static final String DERIVED_INTERFACE = "derived";

    /** Persists one finished run (PostgreSQL in production; replaceable in tests). */
    @FunctionalInterface
    public interface Sink {
        void persist(ExtractionRun run) throws Exception;
    }

    private static final ThreadLocal<RunContext> CURRENT = new ThreadLocal<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile Sink sink = RunLedger::persistToPostgres;

    private RunLedger() {
    }

    // --- lifecycle ----------------------------------------------------------

    /** Wraps one command: begins a run, executes it, then records the run (never throws from recording). */
    public static int run(String command, IntSupplier runner) {
        begin(command);
        int exitCode = 1;
        try {
            exitCode = runner.getAsInt();
            return exitCode;
        } catch (RuntimeException e) {
            error("unexpected error: " + e.getMessage());
            throw e;
        } finally {
            finish(exitCode);
        }
    }

    public static void begin(String command) {
        CURRENT.set(new RunContext(UUID.randomUUID().toString(), command, interfaceFor(command), Instant.now()));
    }

    /** Builds and best-effort persists the ledger row, then clears the thread-local. Never throws. */
    public static void finish(int exitCode) {
        RunContext ctx = CURRENT.get();
        if (ctx == null) {
            return;
        }
        try {
            ExtractionRun run = buildRun(ctx, exitCode, Instant.now());
            try {
                sink.persist(run);
            } catch (Exception e) {
                System.err.println("[run-ledger] could not record extraction run " + run.extractionRunId()
                        + " (" + ctx.command() + "): " + e.getMessage());
            }
        } catch (RuntimeException unexpected) {
            System.err.println("[run-ledger] failed to build run for '" + ctx.command() + "': " + unexpected.getMessage());
        } finally {
            CURRENT.remove();
        }
    }

    /** Abandons the current run without recording it (e.g. a usage error, not an import). */
    public static void discard() {
        CURRENT.remove();
    }

    // --- reporting (no-ops outside a run) -------------------------------------

    public static void record(RunContext.EntityCount count) {
        RunContext ctx = CURRENT.get();
        if (ctx != null) {
            ctx.addEntity(count);
        }
    }

    public static void error(String message) {
        RunContext ctx = CURRENT.get();
        if (ctx != null) {
            ctx.setError(message);
        }
    }

    public static void window(Instant start, Instant end) {
        RunContext ctx = CURRENT.get();
        if (ctx != null) {
            ctx.setWindow(start, end);
        }
    }

    /**
     * {@code derived} for reconcile-* and derive-* commands (they read loaded tables, not the KF Agent), else the
     * KF Agent.
     */
    static String interfaceFor(String command) {
        return command != null && (command.startsWith("reconcile-") || command.startsWith("derive-"))
                ? DERIVED_INTERFACE : SOURCE_INTERFACE;
    }

    /** The current run's id, or {@code null} outside {@link #run}. */
    public static String currentRunId() {
        RunContext ctx = CURRENT.get();
        return ctx == null ? null : ctx.runId();
    }

    /**
     * Read-only view of the current run as {@link #finish} will record it for this exit code (status, counts, error),
     * or {@code null} outside a run (e.g. after a usage error discarded it). Persists nothing; the run stays open.
     */
    public static ExtractionRun snapshot(int exitCode) {
        RunContext ctx = CURRENT.get();
        return ctx == null ? null : buildRun(ctx, exitCode, Instant.now());
    }

    /** The current run's id, or a fresh UUID when no run is active (library / test callers). */
    public static String runIdOrNew() {
        String id = currentRunId();
        return id != null ? id : UUID.randomUUID().toString();
    }

    // --- helpers ------------------------------------------------------------

    /** Pure: turns a context + exit code into the immutable ledger row (unit-testable). */
    static ExtractionRun buildRun(RunContext ctx, int exitCode, Instant ended) {
        int extracted = 0;
        int inserted = 0;
        int updated = 0;
        int failed = 0;
        for (RunContext.EntityCount e : ctx.entities()) {
            extracted += e.extracted();
            inserted += e.inserted();
            updated += e.updated();
            failed += e.failed();
        }
        String status = statusFor(exitCode, failed);
        String error = ctx.errorMessage();
        if (error == null && !"SUCCESS".equals(status)) {
            error = reasonFor(exitCode) + " (exit code " + exitCode + ")";
        }
        boolean none = ctx.entities().isEmpty();
        return new ExtractionRun(ctx.runId(), ctx.command(), ctx.sourceInterface(), status,
                ctx.startedAt(), ended, ended.toEpochMilli() - ctx.startedAt().toEpochMilli(),
                ctx.windowStart(), ctx.windowEnd(),
                none ? null : extracted, none ? null : inserted, none ? null : updated, none ? null : failed,
                entityCountsJson(ctx), error);
    }

    /**
     * Part 1's statuses, plus INCOMPLETE for Part 2's exit 7 (rows imported, but the scan's / sweep's completeness
     * could not be proven).
     */
    static String statusFor(int exitCode, int failed) {
        if (exitCode == 0) {
            return failed > 0 ? "COMPLETED_WITH_FAILURES" : "SUCCESS";
        }
        if (exitCode == 6) {
            return "COMPLETED_WITH_FAILURES";
        }
        if (exitCode == 7) {
            return "INCOMPLETE";
        }
        return "FAILED";
    }

    /** Human reason per Part 2's exit-code convention. */
    static String reasonFor(int exitCode) {
        return switch (exitCode) {
            case 3 -> "configuration error";
            case 4 -> "KF Agent HTTP/source error";
            case 5 -> "PostgreSQL error";
            case 6 -> "completed with row-level failures";
            case 7 -> "scan / sweep completeness not confirmed";
            default -> "unexpected error";
        };
    }

    private static String entityCountsJson(RunContext ctx) {
        if (ctx.entities().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (RunContext.EntityCount e : ctx.entities()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entity", e.entity());
            m.put("extracted", e.extracted());
            m.put("inserted", e.inserted());
            m.put("updated", e.updated());
            m.put("failed", e.failed());
            m.put("skipped", e.skipped());
            if (e.sweepStatus() != null) {
                m.put("sweep", e.sweepStatus());
                m.put("marked_deleted", e.marked());
                m.put("revived", e.revived());
            }
            list.add(m);
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception ex) {
            return null;
        }
    }

    private static void persistToPostgres(ExtractionRun run) throws Exception {
        AppConfig config = AppConfig.loadDatabaseOnly(); // the ledger only needs PostgreSQL
        try (Connection conn = PostgresConnection.open(config)) {
            RunLedgerRepository repo = new RunLedgerRepository(config.pgSchema());
            repo.ensureTargetTable(conn);
            repo.insert(conn, run);
        }
    }

    /** Test hook: replaces the persistence sink; returns the previous one. */
    public static Sink useSink(Sink replacement) {
        Sink previous = sink;
        sink = replacement;
        return previous;
    }
}

package com.keyforge.part2.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable accumulator for one import command's ledger entry (Part 1 {@code iiq.runledger.RunContext}): run id,
 * command, source interface, start time, optional window, per-table counts and error text.
 */
public final class RunContext {

    /**
     * Per-table counts. {@code skipped} (append-only rows that already existed) and the sweep fields are Part 2
     * additions; the sweep fields are {@code null} when the table was not eligible for a sweep.
     */
    public record EntityCount(String entity, int extracted, int inserted, int updated, int failed, int skipped,
                              String sweepStatus, Integer marked, Integer revived) {
    }

    private final String runId;
    private final String command;
    private final String sourceInterface;
    private final Instant startedAt;
    private final List<EntityCount> entities = new ArrayList<>();
    private Instant windowStart;
    private Instant windowEnd;
    private String errorMessage;

    public RunContext(String runId, String command, String sourceInterface, Instant startedAt) {
        this.runId = runId;
        this.command = command;
        this.sourceInterface = sourceInterface;
        this.startedAt = startedAt;
    }

    public String runId() { return runId; }
    public String command() { return command; }
    public String sourceInterface() { return sourceInterface; }
    public Instant startedAt() { return startedAt; }
    public List<EntityCount> entities() { return entities; }
    public Instant windowStart() { return windowStart; }
    public Instant windowEnd() { return windowEnd; }
    public String errorMessage() { return errorMessage; }

    public void addEntity(EntityCount count) {
        entities.add(count);
    }

    public void setWindow(Instant start, Instant end) {
        this.windowStart = start;
        this.windowEnd = end;
    }

    /** Records the first non-blank error message (later ones are appended). */
    public void setError(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        this.errorMessage = errorMessage == null ? message : errorMessage + " | " + message;
    }
}

package com.keyforge.part2.ledger;

import java.time.Instant;

/**
 * One immutable {@code kf_extraction_run} row: one per Part 2 import command execution. Same shape as Part 1's
 * {@code iiq.runledger.ExtractionRun}: aggregate totals, a per-table JSON breakdown, timing / window, source interface,
 * status and error. {@code extractionRunId} is the same id stamped on every row the run wrote.
 */
public record ExtractionRun(
        String extractionRunId,
        String command,
        String sourceInterface,
        String status,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        Instant windowStart,
        Instant windowEnd,
        Integer extracted,
        Integer inserted,
        Integer updated,
        Integer failed,
        String entityCountsJson,
        String errorMessage,
        String requestJson) {

    /** The same run without a recorded request (full runs, derived commands). */
    public ExtractionRun(String extractionRunId, String command, String sourceInterface, String status,
                         Instant startedAt, Instant endedAt, Long durationMs, Instant windowStart, Instant windowEnd,
                         Integer extracted, Integer inserted, Integer updated, Integer failed, String entityCountsJson,
                         String errorMessage) {
        this(extractionRunId, command, sourceInterface, status, startedAt, endedAt, durationMs, windowStart, windowEnd,
                extracted, inserted, updated, failed, entityCountsJson, errorMessage, null);
    }
}

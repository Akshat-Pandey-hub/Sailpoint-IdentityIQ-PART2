package com.keyforge.part2.ingest;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What one import run proved about its scan, collected as the run goes and handed to {@link SoftDeleteSweeper}. The
 * sweeper sweeps only when ALL of these hold: full mode (not {@code modifiedAfter}), no exception, pagination ran to
 * its end, and completeness was explicitly verified (with a stated reason) and not contradicted.
 *
 * <p>Usage in an import loop: {@link #keep} every row's primary key before writing it (as Part 1 does), report a write
 * failure with {@link #rowFailed} (its key stays protected), call {@link #paginationComplete} when the loop ends on an
 * empty or short page, {@link #completenessVerified} once the scan is proven complete (e.g. a matching source count),
 * {@link #completenessFailed} if a check fails, and {@link #abort} from any exception handler.
 */
public final class SweepEvidence {

    private final boolean incremental;
    private final Set<String> keepKeys = new LinkedHashSet<>();
    private final Set<String> failedKeys = new LinkedHashSet<>();
    private int failedRows;
    private int unkeyedFailures;
    private boolean paginationComplete;
    private String verifiedBy;
    private String completenessFailure;
    private String abortReason;

    private SweepEvidence(boolean incremental) {
        this.incremental = incremental;
    }

    /** A full scan of the source population (the only mode that may sweep). */
    public static SweepEvidence fullScan() {
        return new SweepEvidence(false);
    }

    /** An incremental ({@code modifiedAfter}) run: it sees only changed rows, so it can never sweep. */
    public static SweepEvidence incremental() {
        return new SweepEvidence(true);
    }

    /** Records a row's primary key as present in the source (call before writing the row). */
    public void keep(String key) {
        if (key != null && !key.isBlank()) {
            keepKeys.add(key.trim());
        }
    }

    /** Records a failed row write. Its key (if known) is protected from deletion; an unknown key cannot be protected. */
    public void rowFailed(String keyOrNull) {
        failedRows++;
        if (keyOrNull == null || keyOrNull.isBlank()) {
            unkeyedFailures++;
        } else {
            failedKeys.add(keyOrNull.trim());
        }
    }

    /** The paging loop reached its end (an empty or short page), without an exception. */
    public void paginationComplete() {
        paginationComplete = true;
    }

    /** The scan was proven complete; {@code how} says how (e.g. "extracted 424 == sourceCount 424"). */
    public void completenessVerified(String how) {
        if (how == null || how.isBlank()) {
            throw new IllegalArgumentException("completeness verification needs a stated reason");
        }
        verifiedBy = how;
    }

    /** A completeness check failed (e.g. source count mismatch); this vetoes the sweep. */
    public void completenessFailed(String why) {
        completenessFailure = (why == null || why.isBlank()) ? "completeness check failed" : why;
    }

    /** The scan threw; this vetoes the sweep. */
    public void abort(Throwable cause) {
        abortReason = cause == null ? "aborted" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    public boolean incrementalRun() { return incremental; }
    public boolean aborted() { return abortReason != null; }
    public String abortReason() { return abortReason; }
    public boolean paginationCompleted() { return paginationComplete; }
    public boolean completenessVerified() { return verifiedBy != null; }
    public String verifiedBy() { return verifiedBy; }
    public String completenessFailure() { return completenessFailure; }
    public int failedRows() { return failedRows; }
    public int unkeyedFailures() { return unkeyedFailures; }
    public Set<String> keepKeys() { return Collections.unmodifiableSet(keepKeys); }
    public Set<String> failedKeys() { return Collections.unmodifiableSet(failedKeys); }
}

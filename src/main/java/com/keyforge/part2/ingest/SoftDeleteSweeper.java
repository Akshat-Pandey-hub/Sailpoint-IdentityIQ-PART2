package com.keyforge.part2.ingest;

import com.keyforge.part2.config.SchemaName;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Reusable soft-delete sweep for current-state tables with a {@code uuid} primary key and {@code is_deleted} /
 * {@code deleted_at} columns. Reproduces Part 1's {@code iiq.deletion.SoftDeleteSweeper} SQL:
 * <ul>
 *   <li>mark: {@code UPDATE t SET is_deleted = true, deleted_at = now() WHERE NOT (pk = ANY(?)) AND is_deleted = false}
 *       — a row already deleted keeps its original {@code deleted_at};</li>
 *   <li>revive: {@code UPDATE t SET is_deleted = false, deleted_at = NULL WHERE pk = ANY(?) AND is_deleted = true}.</li>
 * </ul>
 * Rows are never hard-deleted. Unlike Part 1 the two statements run in ONE transaction (all or nothing).
 *
 * <p>The sweep runs only when {@link #decide} returns {@link Status#SWEPT}: an explicit non-NEVER policy, a full (not
 * {@code modifiedAfter}) run, no exception, pagination complete, completeness verified and not contradicted, the
 * policy's failure rule satisfied, a non-empty keep-set (an empty response never proves every row was deleted), and
 * keep-set keys that are valid UUIDs. Keys of failed rows are added to the keep-set, so they are never marked.
 */
public final class SoftDeleteSweeper {

    /** What the sweep did, or why it was skipped. */
    public enum Status {
        SWEPT,
        SKIPPED_POLICY_NEVER,
        SKIPPED_INCREMENTAL,
        SKIPPED_ABORTED,
        SKIPPED_COMPLETENESS_FAILED,
        SKIPPED_PAGINATION_INCOMPLETE,
        SKIPPED_NOT_VERIFIED,
        SKIPPED_ROW_FAILURES,
        SKIPPED_UNPROTECTABLE_FAILURE,
        SKIPPED_EMPTY_KEEP,
        SKIPPED_INVALID_KEY
    }

    /** One table to sweep: unqualified name, primary-key column, and its explicit policy. */
    public record Target(String table, String pkColumn, SweepPolicy policy) {
        public Target {
            table = identifier(table, "table");
            pkColumn = identifier(pkColumn, "primary-key column");
            if (policy == null) {
                throw new IllegalArgumentException("a sweep policy is required for " + table);
            }
            if (policy != SweepPolicy.NEVER && SweepPolicies.isNeverSweepTable(table)) {
                throw new IllegalArgumentException(table + " must never be swept (append-only, report or governance"
                        + " table); policy " + policy + " is not allowed");
            }
        }
    }

    /** Outcome of one sweep attempt. */
    public record Result(String table, Status status, int keepCount, int marked, int revived, String note) {
        public boolean swept() {
            return status == Status.SWEPT;
        }
    }

    private final String schema;

    public SoftDeleteSweeper(String schema) {
        this.schema = SchemaName.validate(schema);
    }

    /** Part 1's mark statement for a schema-qualified table. */
    public static String markSql(String qualifiedTable, String pkColumn) {
        return "UPDATE " + qualifiedTable + " SET is_deleted = true, deleted_at = now() "
                + "WHERE NOT (" + pkColumn + " = ANY(?)) AND is_deleted = false";
    }

    /** Part 1's revive statement for a schema-qualified table. */
    public static String reviveSql(String qualifiedTable, String pkColumn) {
        return "UPDATE " + qualifiedTable + " SET is_deleted = false, deleted_at = NULL "
                + "WHERE " + pkColumn + " = ANY(?) AND is_deleted = true";
    }

    /** The keys that must survive: every key seen plus every failed row's key. */
    static Set<String> protectedKeys(SweepEvidence evidence) {
        Set<String> keys = new LinkedHashSet<>(evidence.keepKeys());
        keys.addAll(evidence.failedKeys());
        return keys;
    }

    /** Pure decision: {@link Status#SWEPT} when the sweep may run, else the first reason it may not. */
    public static Status decide(Target target, SweepEvidence evidence) {
        if (target.policy() == SweepPolicy.NEVER) {
            return Status.SKIPPED_POLICY_NEVER;
        }
        if (evidence.incrementalRun()) {
            return Status.SKIPPED_INCREMENTAL;
        }
        if (evidence.aborted()) {
            return Status.SKIPPED_ABORTED;
        }
        if (evidence.completenessFailure() != null) {
            return Status.SKIPPED_COMPLETENESS_FAILED;
        }
        if (!evidence.paginationCompleted()) {
            return Status.SKIPPED_PAGINATION_INCOMPLETE;
        }
        if (!evidence.completenessVerified()) {
            return Status.SKIPPED_NOT_VERIFIED;
        }
        if (target.policy() == SweepPolicy.SWEEP_UNLESS_ANY_FAILURE && evidence.failedRows() > 0) {
            return Status.SKIPPED_ROW_FAILURES;
        }
        if (evidence.unkeyedFailures() > 0) {
            return Status.SKIPPED_UNPROTECTABLE_FAILURE; // a failed row without a key cannot be kept safe
        }
        Set<String> keys = protectedKeys(evidence);
        if (keys.isEmpty()) {
            return Status.SKIPPED_EMPTY_KEEP;
        }
        for (String k : keys) {
            try {
                UUID.fromString(k);
            } catch (IllegalArgumentException e) {
                return Status.SKIPPED_INVALID_KEY;
            }
        }
        return Status.SWEPT;
    }

    /**
     * Sweeps {@code target} if {@link #decide} allows it; otherwise touches nothing and reports why. Mark and revive run
     * in one transaction; any SQL failure rolls both back and is rethrown.
     */
    public Result sweep(Connection conn, Target target, SweepEvidence evidence) throws SQLException {
        Set<String> keys = protectedKeys(evidence);
        Status status = decide(target, evidence);
        if (status != Status.SWEPT) {
            return new Result(target.table(), status, keys.size(), 0, 0, reason(status, evidence));
        }
        String qualified = schema + "." + target.table();
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        Array ids = null;
        try {
            ids = conn.createArrayOf("uuid", keys.toArray());
            int marked;
            try (PreparedStatement ps = conn.prepareStatement(markSql(qualified, target.pkColumn()))) {
                ps.setArray(1, ids);
                marked = ps.executeUpdate();
            }
            int revived;
            try (PreparedStatement ps = conn.prepareStatement(reviveSql(qualified, target.pkColumn()))) {
                ps.setArray(1, ids);
                revived = ps.executeUpdate();
            }
            conn.commit();
            return new Result(target.table(), Status.SWEPT, keys.size(), marked, revived,
                    "verified by: " + evidence.verifiedBy());
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            if (ids != null) {
                ids.free();
            }
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    private static String reason(Status status, SweepEvidence evidence) {
        return switch (status) {
            case SKIPPED_POLICY_NEVER -> "sweeping is not enabled for this table";
            case SKIPPED_INCREMENTAL -> "incremental (modifiedAfter) run sees only changed rows";
            case SKIPPED_ABORTED -> "scan aborted: " + evidence.abortReason();
            case SKIPPED_COMPLETENESS_FAILED -> evidence.completenessFailure();
            case SKIPPED_PAGINATION_INCOMPLETE -> "pagination did not reach the end";
            case SKIPPED_NOT_VERIFIED -> "scan completeness was not verified";
            case SKIPPED_ROW_FAILURES -> evidence.failedRows() + " row(s) failed; policy skips the sweep on any failure";
            case SKIPPED_UNPROTECTABLE_FAILURE -> evidence.unkeyedFailures() + " failed row(s) without a key";
            case SKIPPED_EMPTY_KEEP -> "empty keep-set (an empty response never proves every row was deleted)";
            case SKIPPED_INVALID_KEY -> "keep-set contains a key that is not a UUID";
            case SWEPT -> "";
        };
    }

    private static String identifier(String name, String what) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("invalid " + what + " name '" + name + "'");
        }
        return name;
    }
}

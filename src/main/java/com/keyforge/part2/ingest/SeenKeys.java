package com.keyforge.part2.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Deletion marking after a complete full run. Part 1 never reports a deletion: a deleted IIQ object simply stops
 * appearing. So a full run records the key of every row it receives in a session temporary table (never in memory),
 * and afterwards marks the table's live rows that were NOT received as {@code is_deleted = true, deleted_at = now()}.
 * Rows are never removed, and a marked row that appears again is revived by its next write.
 *
 * <p>Nothing is marked when the run had a failed row, a row without a key, or received no rows at all, or when the
 * rows to mark would exceed {@code maxDeletePercent} of the table's live rows (a run that ended early must never look
 * like a mass deletion; 100 disables this limit, 0 never marks).
 */
final class SeenKeys {

    private static final String TABLE = "pg_temp.kf_run_seen_keys";

    private SeenKeys() {
    }

    /** Starts an empty seen-key set for this connection's run. */
    static void reset(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE IF NOT EXISTS kf_run_seen_keys (k uuid PRIMARY KEY)");
            st.execute("TRUNCATE " + TABLE);
        }
    }

    /** Records a key; {@code false} when it was already recorded in this run. */
    static boolean add(Connection conn, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + TABLE + " (k) VALUES (?::uuid) ON CONFLICT DO NOTHING")) {
            ps.setString(1, key);
            return ps.executeUpdate() == 1;
        }
    }

    /** Drops the seen-key set. */
    static void drop(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE);
        }
    }

    /**
     * Marks the live rows of {@code qualifiedTable} whose {@code pkColumn} was not seen, when every guard passes.
     *
     * @param failed   rows of this run that failed
     * @param unkeyed  rows of this run whose key could not be derived
     * @param seen     distinct keys recorded in this run
     */
    static SoftDeleteSweeper.Result markMissing(Connection conn, String qualifiedTable, String pkColumn, int failed,
                                                int unkeyed, int seen, int maxDeletePercent) throws SQLException {
        String table = qualifiedTable.substring(qualifiedTable.lastIndexOf('.') + 1);
        if (failed > 0) {
            return skipped(table, SoftDeleteSweeper.Status.SKIPPED_ROW_FAILURES, seen,
                    failed + " row(s) failed; no row was marked deleted");
        }
        if (unkeyed > 0) {
            return skipped(table, SoftDeleteSweeper.Status.SKIPPED_UNPROTECTABLE_FAILURE, seen,
                    unkeyed + " row(s) had no key; no row was marked deleted");
        }
        if (seen == 0) {
            return skipped(table, SoftDeleteSweeper.Status.SKIPPED_EMPTY_KEEP, 0,
                    "the source returned no rows; an empty result never marks every row deleted");
        }
        String missing = "NOT EXISTS (SELECT 1 FROM " + TABLE + " s WHERE s.k = t." + pkColumn + ")";
        int toMark;
        int live;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FILTER (WHERE " + missing + "), count(*) FROM "
                     + qualifiedTable + " AS t WHERE t.is_deleted = false")) {
            rs.next();
            toMark = rs.getInt(1);
            live = rs.getInt(2);
        }
        if (toMark > 0 && (long) toMark * 100 > (long) live * maxDeletePercent) {
            return skipped(table, SoftDeleteSweeper.Status.SKIPPED_SAFETY_LIMIT, seen, "the run would mark " + toMark
                    + " of " + live + " live rows deleted, more than SWEEP_MAX_DELETE_PERCENT=" + maxDeletePercent
                    + "%; nothing was marked (check the source, or raise the limit for a real mass deletion)");
        }
        int marked = 0;
        if (toMark > 0) {
            try (Statement st = conn.createStatement()) {
                marked = st.executeUpdate("UPDATE " + qualifiedTable + " AS t SET is_deleted = true, deleted_at = now() "
                        + "WHERE t.is_deleted = false AND " + missing);
            }
        }
        return new SoftDeleteSweeper.Result(table, SoftDeleteSweeper.Status.SWEPT, seen, marked, 0,
                "complete full run, every row written");
    }

    private static SoftDeleteSweeper.Result skipped(String table, SoftDeleteSweeper.Status status, int seen,
                                                    String note) {
        return new SoftDeleteSweeper.Result(table, status, seen, 0, 0, note);
    }
}

package com.keyforge.part2.ingest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.StringJoiner;

/**
 * The "write only when something changed" tail shared by the keyed tables. Used after
 * {@code INSERT INTO <table> AS cur (...) VALUES (...)}:
 * <pre>ON CONFLICT (pk) DO UPDATE SET c = EXCLUDED.c, …, extracted_at = now()[, is_deleted = false, deleted_at = null]
 *   WHERE ROW(cur.c, …) IS DISTINCT FROM ROW(EXCLUDED.c, …)[ OR cur.is_deleted]
 *   RETURNING (xmax = 0)</pre>
 * A new key is INSERTED; an existing row is UPDATED only when one of the compared columns differs (or, for a
 * soft-deletable table, when it is marked deleted, which revives it); otherwise nothing is written and no row is
 * returned: SKIPPED. The compared columns are the row's data columns, never the per-run lineage
 * ({@code extraction_run_id}, {@code extracted_at}).
 */
final class UpsertSql {

    /** Alias every caller gives the target table in its INSERT. */
    static final String ALIAS = "cur";

    private UpsertSql() {
    }

    /**
     * @param pk         the conflict (primary-key) column
     * @param compared   data columns: compared, and set from the incoming row when they differ
     * @param alsoSet    columns set from the incoming row on update but not compared (e.g. lineage, run id)
     * @param softDelete the table has {@code is_deleted}/{@code deleted_at}: an update revives the row
     */
    static String tail(String pk, List<String> compared, List<String> alsoSet, boolean softDelete) {
        StringJoiner set = new StringJoiner(", ");
        StringJoiner current = new StringJoiner(", ");
        StringJoiner incoming = new StringJoiner(", ");
        for (String c : compared) {
            set.add(c + " = EXCLUDED." + c);
            current.add(ALIAS + "." + c);
            incoming.add("EXCLUDED." + c);
        }
        for (String c : alsoSet) {
            set.add(c + " = EXCLUDED." + c);
        }
        set.add("extracted_at = now()");
        if (softDelete) {
            set.add("is_deleted = false");
            set.add("deleted_at = null");
        }
        return "ON CONFLICT (" + pk + ") DO UPDATE SET " + set + " WHERE ROW(" + current + ") IS DISTINCT FROM ROW("
                + incoming + ")" + (softDelete ? " OR " + ALIAS + ".is_deleted" : "") + " RETURNING (xmax = 0)";
    }

    /** Reads the outcome of a statement ending in {@link #tail}. */
    static EntityRepository.Outcome outcome(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            return EntityRepository.Outcome.SKIPPED;
        }
        return rs.getBoolean(1) ? EntityRepository.Outcome.INSERTED : EntityRepository.Outcome.UPDATED;
    }
}

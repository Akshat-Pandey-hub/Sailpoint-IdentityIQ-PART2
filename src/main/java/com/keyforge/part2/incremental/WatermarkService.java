package com.keyforge.part2.incremental;

import com.keyforge.part2.config.SchemaName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Watermark orchestration, reusing Part 1's rules ({@code iiq.incremental.WatermarkService},
 * {@code IncrementalFilter.effectiveWatermark}, {@code nativeload.NativeCssExtraction}):
 * <ul>
 *   <li>key = {@code "native:" + table} (Part 1's native watermark key), field {@code "modified"};</li>
 *   <li>bound = stored watermark − overlap window; {@code null} (no bound → full fetch) on the first run;</li>
 *   <li>the new watermark is {@code max(modified_at)} of the destination table after the load — the high-water IIQ
 *       change time captured — and it is written only when the run had zero row failures;</li>
 *   <li>the write itself never moves a watermark backwards ({@link WatermarkRepository#advance}).</li>
 * </ul>
 */
public final class WatermarkService {

    /** The source change-time field the watermark tracks. */
    public static final String WATERMARK_FIELD = "modified";

    private final String schema;
    private final WatermarkRepository repository;

    public WatermarkService(String schema) {
        this.schema = SchemaName.validate(schema);
        this.repository = new WatermarkRepository(this.schema);
    }

    /** Part 1 {@code NativeCssExtraction.watermarkEntity}: {@code native:<table>}. */
    public static String watermarkKey(String table) {
        return "native:" + table.substring(table.lastIndexOf('.') + 1);
    }

    /**
     * Part 1 {@code IncrementalFilter.effectiveWatermark}: {@code null} in full mode or with no stored watermark
     * ("everything"); otherwise the stored watermark minus the overlap (a zero / negative overlap is no overlap).
     */
    public static Instant effectiveBound(Instant stored, ExtractionMode mode, Duration overlap) {
        if (mode != ExtractionMode.INCREMENTAL || stored == null) {
            return null;
        }
        if (overlap == null || overlap.isZero() || overlap.isNegative()) {
            return stored;
        }
        return stored.minus(overlap);
    }

    /** Part 1 {@code WatermarkService.shouldAdvance}: zero row failures and an actual value to store. */
    public static boolean shouldAdvance(int failed, Instant newWatermark) {
        return failed == 0 && newWatermark != null;
    }

    /** Ensures the table and returns the stored watermark for {@code table} ({@code null} = never set). */
    public Instant read(Connection conn, String table) throws SQLException {
        return readKey(conn, watermarkKey(table));
    }

    /** Ensures the table and returns the watermark stored under an explicit {@code key} ({@code null} = never set). */
    public Instant readKey(Connection conn, String key) throws SQLException {
        repository.ensureTargetTable(conn);
        return repository.read(conn, key);
    }

    /** {@code max(modified_at)} of the destination table: the newest IIQ change time captured. */
    public Instant maxModified(Connection conn, String table) throws SQLException {
        return maxValue(conn, table, IncrementalCursor.MODIFIED);
    }

    /** {@code max(<cursor column>)} of the destination table: the newest IIQ time captured for that cursor. */
    public Instant maxValue(Connection conn, String table, IncrementalCursor cursor) throws SQLException {
        String unqualified = table.substring(table.lastIndexOf('.') + 1);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT max(" + cursor.column() + ") FROM " + schema + "." + unqualified)) {
            if (rs.next()) {
                OffsetDateTime odt = rs.getObject(1, OffsetDateTime.class);
                return odt == null ? null : odt.toInstant();
            }
        }
        return null;
    }

    /**
     * Advances the watermark iff {@link #shouldAdvance}; returns {@code true} only if the stored value actually moved
     * forward (or was set for the first time).
     */
    public boolean advanceIfSuccessful(Connection conn, String table, Instant newWatermark, int failed, String runId)
            throws SQLException {
        return advanceIfSuccessful(conn, table, newWatermark, failed, runId, IncrementalCursor.MODIFIED);
    }

    /** Same, recording the cursor's IIQ field ({@code modified} or {@code created}) with the watermark. */
    public boolean advanceIfSuccessful(Connection conn, String table, Instant newWatermark, int failed, String runId,
                                       IncrementalCursor cursor) throws SQLException {
        return advanceKeyIfSuccessful(conn, watermarkKey(table), newWatermark, failed, runId, cursor);
    }

    /** Same, for a watermark stored under an explicit {@code key} (e.g. a parent + children family). */
    public boolean advanceKeyIfSuccessful(Connection conn, String key, Instant newWatermark, int failed, String runId,
                                          IncrementalCursor cursor) throws SQLException {
        if (!shouldAdvance(failed, newWatermark)) {
            return false;
        }
        repository.ensureTargetTable(conn);
        return repository.advance(conn, key, cursor.sourceField(), newWatermark, runId);
    }
}

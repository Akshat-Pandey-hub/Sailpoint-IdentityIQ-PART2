package com.keyforge.part2.incremental;

import com.keyforge.part2.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Watermark rules ported from Part 1, the CLI mode flags, the overlap config, and Part 1's table DDL. */
class WatermarkServiceTest {

    private static final Instant W = Instant.parse("2026-10-09T14:21:07.184Z");

    @Test
    void effectiveBoundIsTheStoredWatermarkMinusTheOverlap() {
        assertNull(WatermarkService.effectiveBound(W, ExtractionMode.FULL, Duration.ofMinutes(5)), "full: no bound");
        assertNull(WatermarkService.effectiveBound(null, ExtractionMode.INCREMENTAL, Duration.ofMinutes(5)),
                "first incremental run: no bound -> full fetch");
        assertEquals(Instant.parse("2026-10-09T14:16:07.184Z"),
                WatermarkService.effectiveBound(W, ExtractionMode.INCREMENTAL, Duration.ofMinutes(5)));
        assertEquals(W, WatermarkService.effectiveBound(W, ExtractionMode.INCREMENTAL, Duration.ZERO));
        assertEquals(W, WatermarkService.effectiveBound(W, ExtractionMode.INCREMENTAL, Duration.ofMinutes(-3)));
        assertEquals(W, WatermarkService.effectiveBound(W, ExtractionMode.INCREMENTAL, null));
    }

    @Test
    void advanceOnlyAfterAFullySuccessfulRun() {
        assertTrue(WatermarkService.shouldAdvance(0, W));
        assertFalse(WatermarkService.shouldAdvance(1, W), "a row failure never advances");
        assertFalse(WatermarkService.shouldAdvance(0, null), "nothing to store");
    }

    @Test
    void keysReusePart1sNativeWatermarkKeys() {
        assertEquals("native:kf_identity", WatermarkService.watermarkKey("iiq_native_part2.kf_identity"));
        assertEquals("native:kf_entitlement", WatermarkService.watermarkKey("kf_entitlement"));
        assertEquals("modified", WatermarkService.WATERMARK_FIELD);
    }

    @Test
    void incrementalIsOptIn() {
        assertEquals(ExtractionMode.FULL, ExtractionMode.fromArgs(new String[0]));
        assertEquals(ExtractionMode.FULL, ExtractionMode.fromArgs(null));
        assertEquals(ExtractionMode.INCREMENTAL, ExtractionMode.fromArgs(new String[]{"--incremental"}));
        assertEquals(ExtractionMode.INCREMENTAL, ExtractionMode.fromArgs(new String[]{"--DELTA"}));
        assertEquals(ExtractionMode.FULL, ExtractionMode.fromArgs(new String[]{"--incremental", "--full"}),
                "the last mode flag wins");
        assertEquals(ExtractionMode.FULL, ExtractionMode.fromArgs(new String[]{"--verbose"}));
    }

    @Test
    void overlapDefaultsToFiveMinutesAndRejectsBadValues() {
        assertEquals(Duration.ofMinutes(5), IncrementalConfig.parseOverlapMinutes(null));
        assertEquals(Duration.ofMinutes(5), IncrementalConfig.parseOverlapMinutes(" "));
        assertEquals(Duration.ofMinutes(30), IncrementalConfig.parseOverlapMinutes("30"));
        assertEquals(Duration.ZERO, IncrementalConfig.parseOverlapMinutes("0"));
        assertThrows(ConfigException.class, () -> IncrementalConfig.parseOverlapMinutes("-1"));
        assertThrows(ConfigException.class, () -> IncrementalConfig.parseOverlapMinutes("5m"));
    }

    @Test
    void tableMatchesPart1AndTheAdvanceNeverMovesBackwards() {
        WatermarkRepository repo = new WatermarkRepository("iiq_native_part2");
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String part : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(", ")) {
            cols.add(part.trim().split("\\s+")[0]);
        }
        assertEquals(List.of("entity", "watermark_field", "source_watermark", "last_run_id", "updated_at"), cols,
                "iiq_native.kf_extraction_watermark column order");
        assertTrue(ddl.contains("entity text PRIMARY KEY") && ddl.contains("source_watermark timestamptz"));
        String sql = repo.advanceSql();
        assertTrue(sql.contains("ON CONFLICT (entity) DO UPDATE SET"));
        assertTrue(sql.endsWith("WHERE iiq_native_part2.kf_extraction_watermark.source_watermark IS NULL "
                + "OR EXCLUDED.source_watermark > iiq_native_part2.kf_extraction_watermark.source_watermark"),
                "the conflict update applies only to a strictly newer watermark");
    }
}

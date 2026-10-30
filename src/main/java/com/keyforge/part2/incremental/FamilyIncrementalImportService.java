package com.keyforge.part2.incremental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.ingest.EntityWriter;
import com.keyforge.part2.ingest.ImportResult;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in incremental import of a parent entity together with the child entities that IIQ builds from it, under ONE
 * shared watermark (e.g. IdentityRequest with its request items and approvals; ProvisioningTransaction with its
 * provisioning items). Children carry no change time of their own, so they are bounded by the parent's change time:
 * the KF Agent returns the children of the parents changed after the bound.
 * <ol>
 *   <li>read the family watermark; bound = watermark − overlap, or none on the first run (full baseline);</li>
 *   <li>page the parent, then each child dataset, with that same bound ({@code modifiedAfter} + {@code start}/
 *       {@code limit}, next start = start + rows received, a short page ends a dataset), upserting each page through
 *       the same writers as their full imports. Only one page is held in memory at a time;</li>
 *   <li>advance the shared watermark to {@code max(coalesce(modified_at, created_at))} of the parent table, capped at
 *       the run's start ({@link WatermarkService#capAtRunStart}), ONLY if no row in the parent or any child failed and
 *       no request failed. Rows already written stay (idempotent upserts), and an unchanged watermark makes the next
 *       run re-read the same window, so a failed page or child can never be skipped.</li>
 * </ol>
 * Never sweeps.
 */
public final class FamilyIncrementalImportService {

    /** The family's watermark key: the parent key plus its child tables, distinct from any parent-only watermark. */
    public static String familyKey(EntityWriter parent, List<EntityWriter> children) {
        StringBuilder key = new StringBuilder(WatermarkService.watermarkKey(parent.targetTable()));
        for (EntityWriter c : children) {
            String t = c.targetTable();
            key.append('+').append(t.substring(t.lastIndexOf('.') + 1));
        }
        return key.toString();
    }

    /**
     * @param results      one result per writer: the parent first, then the children in order
     * @param stored       the family watermark before the run ({@code null} = first run)
     * @param bound        the {@code modifiedAfter} sent to every dataset ({@code null} = full read)
     * @param fetchedAt    when the run started fetching
     * @param newWatermark the watermark this run computed
     * @param advanced     whether the stored watermark moved (or was first set)
     */
    public record Outcome(List<ImportResult> results, String key, Instant stored, Instant bound, Instant fetchedAt,
                          Instant newWatermark, boolean advanced) {

        public int failed() {
            return results.stream().mapToInt(ImportResult::failed).sum();
        }
    }

    private final IncrementalImportService.Fetcher fetcher;
    private final EntityWriter parent;
    private final List<EntityWriter> children;
    private final WatermarkService watermarks;
    private final Duration overlap;
    private final int pageSize;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public FamilyIncrementalImportService(IncrementalImportService.Fetcher fetcher, EntityWriter parent,
                                          List<EntityWriter> children, WatermarkService watermarks,
                                          Duration overlap) {
        this(fetcher, parent, children, watermarks, overlap, IncrementalImportService.DEFAULT_PAGE_SIZE,
                Clock.systemUTC());
    }

    public FamilyIncrementalImportService(IncrementalImportService.Fetcher fetcher, EntityWriter parent,
                                          List<EntityWriter> children, WatermarkService watermarks,
                                          Duration overlap, int pageSize, Clock clock) {
        this.fetcher = fetcher;
        this.parent = parent;
        this.children = List.copyOf(children);
        this.watermarks = watermarks;
        this.overlap = overlap;
        this.pageSize = pageSize > 0 ? pageSize : IncrementalImportService.DEFAULT_PAGE_SIZE;
        this.clock = clock;
    }

    public Outcome run(Connection conn, String extractionRunId) throws SQLException {
        conn.setAutoCommit(true);
        List<EntityWriter> all = new ArrayList<>();
        all.add(parent);
        all.addAll(children);
        for (EntityWriter w : all) {
            w.ensureSchemaAndTable(conn);
        }

        String key = familyKey(parent, children);
        Instant stored = watermarks.readKey(conn, key);
        Instant bound = WatermarkService.effectiveBound(stored, ExtractionMode.INCREMENTAL, overlap);
        String boundIso = bound == null ? null : bound.toString();
        Instant fetchedAt = clock.instant();

        // page the parent, then each child dataset; any HTTP / JSON failure throws before the watermark step
        List<ImportResult> results = new ArrayList<>();
        for (EntityWriter w : all) {
            results.add(load(conn, w, boundIso, extractionRunId));
        }

        // the shared watermark moves only if nothing failed anywhere in the family
        int failed = results.stream().mapToInt(ImportResult::failed).sum();
        Instant newWatermark = WatermarkService.capAtRunStart(
                watermarks.maxValue(conn, parent.targetTable(), IncrementalCursor.CHANGED), fetchedAt);
        boolean advanced = watermarks.advanceKeyIfSuccessful(conn, key, newWatermark, failed, extractionRunId,
                IncrementalCursor.CHANGED);
        return new Outcome(results, key, stored, bound, fetchedAt, newWatermark, advanced);
    }

    private ImportResult load(Connection conn, EntityWriter writer, String boundIso, String runId) {
        ImportResult result = new ImportResult(writer.entity(), writer.targetTable());
        int start = 0;
        while (true) {
            JsonNode rows = IncrementalImportService.parseArray(writer.resource(),
                    fetcher.fetchPage(writer.resource(), start, pageSize, boundIso), mapper);
            result.countPage();
            for (JsonNode row : rows) {
                result.countFetched(1);
                try {
                    switch (writer.upsert(conn, row, runId)) {
                        case INSERTED -> result.countInserted();
                        case UPDATED -> result.countUpdated();
                        case SKIPPED -> result.countSkipped();
                    }
                } catch (SQLException | RuntimeException e) {
                    result.countFailure(writer.ref(row), e.getMessage());
                }
            }
            if (rows.size() < pageSize) {
                return result;
            }
            start += rows.size();
        }
    }
}

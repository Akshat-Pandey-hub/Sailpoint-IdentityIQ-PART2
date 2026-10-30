package com.keyforge.part2.incremental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.KfAgentClient;
import com.keyforge.part2.ingest.EntityWriter;
import com.keyforge.part2.ingest.ImportResult;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Opt-in incremental import for an entity whose KF Agent endpoint supports {@code modifiedAfter}, reproducing Part 1's
 * {@code NativeCssExtraction} lifecycle over paged requests:
 * <ol>
 *   <li>read the stored watermark ({@code native:<table>}); bound = watermark − overlap, or none on the first run
 *       (a full read that establishes the baseline);</li>
 *   <li>page the changed rows ({@code ?modifiedAfter=<bound>&start=&limit=}): Part 1 applies the bound inside IIQ and
 *       serves each window directly, so no request reads or counts the whole entity. Next {@code start} = previous
 *       {@code start} + rows received; a page shorter than the page size (or empty) is the end;</li>
 *   <li>upsert every returned row through the entity's ordinary writer (same rows, same mapping, same lineage, and
 *       the writer's own revive of {@code is_deleted});</li>
 *   <li>advance the watermark only if no row failed, to the newest change time loaded but never later than the time
 *       the run started ({@link WatermarkService#capAtRunStart}): a row that changed while the pages were being read
 *       can be passed over by the offset window, and the cap makes the next run read it. The advance never moves the
 *       watermark back.</li>
 * </ol>
 * An incremental run sees only changed rows, so it NEVER runs a deletion sweep (this service has no sweeper). Any
 * HTTP / parse / database exception propagates before step 4, so an interrupted run never advances the watermark.
 */
public final class IncrementalImportService {

    /** Fetches one page of the population changed after {@code modifiedAfterIso} ({@code null} = everything). */
    @FunctionalInterface
    public interface Fetcher {
        String fetchPage(String resource, int start, int limit, String modifiedAfterIso);
    }

    /** Default page size when none is configured (same as {@code KFAGENT_PAGE_SIZE}'s default). */
    static final int DEFAULT_PAGE_SIZE = 500;

    /**
     * @param stored        the watermark before the run ({@code null} = first run)
     * @param bound         the {@code modifiedAfter} sent ({@code null} = full read)
     * @param fetchedAt     when the run started fetching (the run window's end, and the watermark's upper limit)
     * @param newWatermark  the watermark this run computed ({@code null} if nothing was read)
     * @param advanced      whether the stored watermark moved (or was first set)
     */
    public record Outcome(ImportResult result, Instant stored, Instant bound, Instant fetchedAt,
                          Instant newWatermark, boolean advanced) { }

    private final Fetcher fetcher;
    private final EntityWriter writer;
    private final WatermarkService watermarks;
    private final Duration overlap;
    private final IncrementalCursor cursor;
    private final int pageSize;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public IncrementalImportService(Fetcher fetcher, EntityWriter writer, WatermarkService watermarks,
                                    Duration overlap) {
        this(fetcher, writer, watermarks, overlap, IncrementalCursor.MODIFIED);
    }

    /**
     * @param cursor which IIQ timestamp the bound and the watermark follow: {@code modified} for current-state
     *               objects, {@code created} for append-only event objects
     */
    public IncrementalImportService(Fetcher fetcher, EntityWriter writer, WatermarkService watermarks,
                                    Duration overlap, IncrementalCursor cursor) {
        this(fetcher, writer, watermarks, overlap, cursor, DEFAULT_PAGE_SIZE, Clock.systemUTC());
    }

    public IncrementalImportService(Fetcher fetcher, EntityWriter writer, WatermarkService watermarks,
                                    Duration overlap, IncrementalCursor cursor, int pageSize, Clock clock) {
        this.fetcher = fetcher;
        this.writer = writer;
        this.watermarks = watermarks;
        this.overlap = overlap;
        this.cursor = cursor;
        this.pageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        this.clock = clock;
    }

    public Outcome run(Connection conn, String extractionRunId) throws SQLException {
        conn.setAutoCommit(true);
        writer.ensureSchemaAndTable(conn);

        Instant stored = watermarks.read(conn, writer.targetTable());
        Instant bound = WatermarkService.effectiveBound(stored, ExtractionMode.INCREMENTAL, overlap);
        String boundIso = bound == null ? null : bound.toString();
        Instant fetchedAt = clock.instant();

        ImportResult result = new ImportResult(writer.entity(), writer.targetTable());
        int start = 0;
        while (true) {
            JsonNode rows = parseArray(writer.resource(),
                    fetcher.fetchPage(writer.resource(), start, pageSize, boundIso), mapper);
            result.countPage();
            for (JsonNode row : rows) {
                result.countFetched(1);
                try {
                    switch (writer.upsert(conn, row, extractionRunId)) {
                        case INSERTED -> result.countInserted();
                        case UPDATED -> result.countUpdated();
                        case SKIPPED -> result.countSkipped();
                    }
                } catch (SQLException | RuntimeException e) {
                    result.countFailure(writer.ref(row), e.getMessage());
                }
            }
            if (rows.size() < pageSize) {
                break;
            }
            start += rows.size();
        }

        Instant newWatermark = WatermarkService.capAtRunStart(
                watermarks.maxValue(conn, writer.targetTable(), cursor), fetchedAt);
        boolean advanced = watermarks.advanceIfSuccessful(conn, writer.targetTable(), newWatermark, result.failed(),
                extractionRunId, cursor);
        return new Outcome(result, stored, bound, fetchedAt, newWatermark, advanced);
    }

    /** Parses one KF Agent page: a bare JSON array, else a {@link KfAgentClient.KfAgentHttpException}. */
    static JsonNode parseArray(String resource, String body, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new KfAgentClient.KfAgentHttpException(
                    "KF Agent /kfagent/" + resource + " did not return JSON: " + e.getMessage());
        }
        if (root == null || !root.isArray()) {
            String note = (root != null && root.has("error")) ? (": " + root.get("error").asText()) : "";
            throw new KfAgentClient.KfAgentHttpException(
                    "KF Agent /kfagent/" + resource + " returned a non-array response" + note);
        }
        return root;
    }
}

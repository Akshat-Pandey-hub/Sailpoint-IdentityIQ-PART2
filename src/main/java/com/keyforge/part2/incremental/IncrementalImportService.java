package com.keyforge.part2.incremental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.KfAgentClient;
import com.keyforge.part2.ingest.EntityWriter;
import com.keyforge.part2.ingest.ImportResult;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/**
 * Opt-in incremental import for an entity whose KF Agent endpoint supports {@code modifiedAfter} (identity, account,
 * application, entitlement, role), reproducing Part 1's {@code NativeCssExtraction} lifecycle:
 * <ol>
 *   <li>read the stored watermark ({@code native:<table>}); bound = watermark − overlap, or none on the first run
 *       (a full fetch that establishes the baseline);</li>
 *   <li>fetch the changed rows in ONE unwindowed request ({@code ?modifiedAfter=<bound>}): one KF Agent scan, so the
 *       result is not stitched together from pages of different scans;</li>
 *   <li>upsert every returned row through the entity's ordinary writer (same rows, same mapping, same lineage, and
 *       the writer's own revive of {@code is_deleted});</li>
 *   <li>advance the watermark to {@code max(modified_at)} only if no row failed; the advance never moves it back.</li>
 * </ol>
 * An incremental run sees only changed rows, so it NEVER runs a deletion sweep (this service has no sweeper). Any
 * HTTP / parse / database exception propagates before step 4, so an interrupted run never advances the watermark.
 */
public final class IncrementalImportService {

    /** Fetches the population changed after {@code modifiedAfterIso} ({@code null} = everything). */
    @FunctionalInterface
    public interface Fetcher {
        KfAgentClient.Snapshot fetch(String resource, String modifiedAfterIso);
    }

    /**
     * @param stored        the watermark before the run ({@code null} = first run)
     * @param bound         the {@code modifiedAfter} sent ({@code null} = full fetch)
     * @param fetchedAt     when the fetch was issued (the run window's end)
     * @param newWatermark  {@code max(modified_at)} after the load ({@code null} if nothing was read)
     * @param advanced      whether the stored watermark moved (or was first set)
     */
    public record Outcome(ImportResult result, Instant stored, Instant bound, Instant fetchedAt,
                          Instant newWatermark, boolean advanced) { }

    private final Fetcher fetcher;
    private final EntityWriter writer;
    private final WatermarkService watermarks;
    private final Duration overlap;
    private final ObjectMapper mapper = new ObjectMapper();

    public IncrementalImportService(Fetcher fetcher, EntityWriter writer, WatermarkService watermarks,
                                    Duration overlap) {
        this.fetcher = fetcher;
        this.writer = writer;
        this.watermarks = watermarks;
        this.overlap = overlap;
    }

    public Outcome run(Connection conn, String extractionRunId) throws SQLException {
        conn.setAutoCommit(true);
        writer.ensureSchemaAndTable(conn);

        Instant stored = watermarks.read(conn, writer.targetTable());
        Instant bound = WatermarkService.effectiveBound(stored, ExtractionMode.INCREMENTAL, overlap);
        Instant fetchedAt = Instant.now();
        KfAgentClient.Snapshot snapshot = fetcher.fetch(writer.resource(), bound == null ? null : bound.toString());
        JsonNode rows = parseArray(snapshot.body());

        ImportResult result = new ImportResult(writer.entity(), writer.targetTable());
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

        Instant newWatermark = watermarks.maxModified(conn, writer.targetTable());
        boolean advanced = watermarks.advanceIfSuccessful(conn, writer.targetTable(), newWatermark, result.failed(),
                extractionRunId);
        return new Outcome(result, stored, bound, fetchedAt, newWatermark, advanced);
    }

    private JsonNode parseArray(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new KfAgentClient.KfAgentHttpException(
                    "KF Agent /kfagent/" + writer.resource() + " did not return JSON: " + e.getMessage());
        }
        if (root == null || !root.isArray()) {
            String note = (root != null && root.has("error")) ? (": " + root.get("error").asText()) : "";
            throw new KfAgentClient.KfAgentHttpException(
                    "KF Agent /kfagent/" + writer.resource() + " returned a non-array response" + note);
        }
        return root;
    }
}

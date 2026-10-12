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
import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in incremental import of a parent entity together with the child entities that IIQ builds from it, under ONE
 * shared watermark (e.g. IdentityRequest with its request items and approvals; ProvisioningTransaction with its
 * provisioning items). Children carry no change time of their own, so they are bounded by the parent's change time:
 * the KF Agent returns the children of the parents changed after the bound.
 * <ol>
 *   <li>read the family watermark; bound = watermark − overlap, or none on the first run (full baseline);</li>
 *   <li>fetch the parent and EVERY child dataset with that same bound, and parse them all, before writing anything:
 *       an HTTP / JSON error therefore writes nothing and leaves the watermark unchanged;</li>
 *   <li>upsert the parent rows, then each child dataset, through the same writers as their full imports;</li>
 *   <li>advance the shared watermark to {@code max(coalesce(modified_at, created_at))} of the parent table ONLY if no
 *       row in the parent or any child failed. Rows already written stay (idempotent upserts), and the unchanged
 *       watermark makes the next run re-read the same window, so a failed child can never be skipped.</li>
 * </ol>
 * Never sweeps (the periodic full imports detect deletions).
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
     * @param bound        the {@code modifiedAfter} sent to every dataset ({@code null} = full fetch)
     * @param fetchedAt    when the first fetch was issued
     * @param newWatermark {@code max(coalesce(modified_at, created_at))} of the parent table after the load
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
    private final ObjectMapper mapper = new ObjectMapper();

    public FamilyIncrementalImportService(IncrementalImportService.Fetcher fetcher, EntityWriter parent,
                                          List<EntityWriter> children, WatermarkService watermarks,
                                          Duration overlap) {
        this.fetcher = fetcher;
        this.parent = parent;
        this.children = List.copyOf(children);
        this.watermarks = watermarks;
        this.overlap = overlap;
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
        Instant fetchedAt = Instant.now();

        // 1. fetch and parse everything first: any HTTP / JSON failure throws here, before a single write
        List<JsonNode> payloads = new ArrayList<>();
        for (EntityWriter w : all) {
            payloads.add(parseArray(w, fetcher.fetch(w.resource(), boundIso).body()));
        }

        // 2. write the parent, then each child dataset
        List<ImportResult> results = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            results.add(write(conn, all.get(i), payloads.get(i), extractionRunId));
        }

        // 3. the shared watermark moves only if nothing failed anywhere in the family
        int failed = results.stream().mapToInt(ImportResult::failed).sum();
        Instant newWatermark = watermarks.maxValue(conn, parent.targetTable(), IncrementalCursor.CHANGED);
        boolean advanced = watermarks.advanceKeyIfSuccessful(conn, key, newWatermark, failed, extractionRunId,
                IncrementalCursor.CHANGED);
        return new Outcome(results, key, stored, bound, fetchedAt, newWatermark, advanced);
    }

    private static ImportResult write(Connection conn, EntityWriter writer, JsonNode rows, String runId) {
        ImportResult result = new ImportResult(writer.entity(), writer.targetTable());
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
        return result;
    }

    private JsonNode parseArray(EntityWriter writer, String body) {
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

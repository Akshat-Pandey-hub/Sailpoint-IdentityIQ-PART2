package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.KfAgentClient;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Full-snapshot import + soft-delete sweep for a table enabled in {@link SweepPolicies}, reproducing Part 1's
 * import-then-sweep lifecycle over the KF Agent REST contract:
 * <ol>
 *   <li>Fetch the WHOLE population in one request ({@link KfAgentClient#fetchSnapshot}): one KF Agent scan, so one
 *       consistent snapshot. (Paged requests would each re-run the agent's scan and could mix snapshots.)</li>
 *   <li>Upsert every row exactly as the ordinary import does, recording each row's primary key in the keep-set BEFORE
 *       writing it (Part 1), and a failed row's key as protected.</li>
 *   <li>Prove completeness from the agent's {@code X-KF-Verified-Source-Count} (its complete-scan guard passed for that
 *       scan): required to be present, and for {@link SweepPolicies.Completeness#ROW_COUNT} equal to the row count.</li>
 *   <li>If the sweep is otherwise eligible, fetch the population a SECOND time (confirmation scan), validate it
 *       independently (valid JSON array, verified count header, row count where the policy requires it, a key for
 *       every row) and add its keys to the keep-set. Its rows are never written. A row is marked deleted only if it is
 *       absent from BOTH verified scans; this protects a live row that the agent's offset-paged plugin scan skipped
 *       because a source object was removed mid-scan (the agent's count check compares against the last page's
 *       count, so such a skip can still pass it).</li>
 *   <li>Only then hand the evidence to {@link SoftDeleteSweeper}, which applies every remaining guard (policy, failures,
 *       empty keep-set, key validity) and runs mark + revive in one transaction.</li>
 * </ol>
 * Any HTTP / parse / database exception (in either scan) propagates before the sweep, so no sweep ever follows a
 * failed scan. The confirmation scan narrows the paging race, it does not eliminate it: two scans that both skip the
 * same live row could still let it be marked (it is revived by the next run that sees it).
 */
public final class SnapshotSweepImportService {

    /** Whole-population fetch ({@code KfAgentClient::fetchSnapshot} in production). */
    @FunctionalInterface
    public interface SnapshotFetcher {
        KfAgentClient.Snapshot fetch(String resource);
    }

    /** The ordinary import counts plus what the sweep did (or why it was skipped). */
    public record Outcome(ImportResult result, SoftDeleteSweeper.Result sweep) { }

    private final SnapshotFetcher fetcher;
    private final EntityWriter writer;
    private final SweepPolicies.SweepConfig config;
    private final SoftDeleteSweeper sweeper;
    private final boolean incremental;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param incremental {@code true} for a {@code modifiedAfter}-bounded run: rows are imported but the sweep never runs
     */
    public SnapshotSweepImportService(SnapshotFetcher fetcher, EntityWriter writer, SweepPolicies.SweepConfig config,
                                      SoftDeleteSweeper sweeper, boolean incremental) {
        this.fetcher = fetcher;
        this.writer = writer;
        this.config = config;
        this.sweeper = sweeper;
        this.incremental = incremental;
    }

    public Outcome run(Connection conn, String extractionRunId) throws SQLException {
        conn.setAutoCommit(true);
        writer.ensureSchemaAndTable(conn);

        ImportResult result = new ImportResult(writer.entity(), writer.targetTable());
        SweepEvidence evidence = incremental ? SweepEvidence.incremental() : SweepEvidence.fullScan();

        KfAgentClient.Snapshot snapshot = fetcher.fetch(writer.resource()); // throws on HTTP / transport errors
        JsonNode rows = parseArray(snapshot.body());                         // throws on a non-array / error body
        result.countPage();

        for (JsonNode row : rows) {
            result.countFetched(1);
            String key = keyOf(row);
            if (key == null) {
                evidence.completenessFailed("a row has no primary key (" + writer.ref(row) + ")");
            } else {
                evidence.keep(key);
            }
            try {
                switch (writer.upsert(conn, row, extractionRunId)) {
                    case INSERTED -> result.countInserted();
                    case UPDATED -> result.countUpdated();
                    case SKIPPED -> result.countSkipped();
                }
            } catch (SQLException | RuntimeException e) {
                result.countFailure(writer.ref(row), e.getMessage());
                evidence.rowFailed(key);
            }
        }

        evidence.paginationComplete(); // the whole population arrived in one unwindowed response
        String firstFailure = check("scan", snapshot.verifiedSourceCount(), rows.size());
        if (firstFailure != null) {
            evidence.completenessFailed(firstFailure);
        } else {
            evidence.completenessVerified(verifiedReason(snapshot.verifiedSourceCount(), rows.size()));
            if (SoftDeleteSweeper.decide(config.target(), evidence) == SoftDeleteSweeper.Status.SWEPT) {
                confirm(snapshot.verifiedSourceCount(), rows.size(), evidence);
            }
        }
        SoftDeleteSweeper.Result sweep = sweeper.sweep(conn, config.target(), evidence);
        return new Outcome(result, sweep);
    }

    /**
     * The confirmation scan: an independent second fetch, validated on its own, whose keys join the keep-set. Its rows
     * are never written. Any failure here vetoes the sweep; an exception propagates.
     */
    private void confirm(int firstCount, int firstRows, SweepEvidence evidence) {
        KfAgentClient.Snapshot second = fetcher.fetch(writer.resource()); // throws on HTTP / transport errors
        JsonNode rows = parseArray(second.body());                       // throws on a non-array / error body
        String failure = check("confirmation scan", second.verifiedSourceCount(), rows.size());
        if (failure != null) {
            evidence.completenessFailed(failure);
            return;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String key = keyOf(row);
            if (key == null) {
                evidence.completenessFailed("confirmation scan: a row has no primary key (" + writer.ref(row) + ")");
                return;
            }
            keys.add(key);
        }
        keys.forEach(evidence::keep); // keep-set = union of both verified scans
        evidence.completenessVerified(verifiedReason(firstCount, firstRows)
                + "; confirmed by a second verified scan (count " + second.verifiedSourceCount() + ", "
                + rows.size() + " rows); keep-set = union of both scans");
    }

    /** {@code null} when a scan's evidence is acceptable, else why not. */
    private String check(String scan, Integer verifiedCount, int rowCount) {
        if (verifiedCount == null || verifiedCount < 0) {
            return scan + ": KF Agent did not report " + KfAgentClient.VERIFIED_SOURCE_COUNT_HEADER
                    + " for /kfagent/" + writer.resource() + " (older agent, or IIQ reported no source count)";
        }
        if (config.completeness() == SweepPolicies.Completeness.ROW_COUNT && rowCount != verifiedCount) {
            return scan + " has " + rowCount + " rows but the verified source count is " + verifiedCount;
        }
        return null;
    }

    private String verifiedReason(int verifiedCount, int rowCount) {
        return "single unwindowed KF Agent snapshot; scan verified against IIQ count " + verifiedCount
                + (config.completeness() == SweepPolicies.Completeness.ROW_COUNT
                ? " = " + rowCount + " rows" : " (parent objects, every one scanned)");
    }

    private String keyOf(JsonNode row) {
        try {
            return writer.key(row);
        } catch (RuntimeException e) {
            return null;
        }
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

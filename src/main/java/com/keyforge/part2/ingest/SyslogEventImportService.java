package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.KfAgentClient;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * Streaming, append-only import of {@code /kfagent/syslog-events} into {@code kf_syslog_event} with a confirmed
 * complete-scan guard — the client-side counterpart of Part 1's {@code NativeSyslogEventImportService} guard, now that
 * the KF Agent serves each page directly instead of re-scanning the whole population per request.
 *
 * <p>Each page is fetched, checked, written row by row and released; only counters and the last ordering key are kept,
 * never the population. Guards, in order:
 * <ol>
 *   <li><b>Source count required.</b> Every page must carry {@code X-KF-Source-Count}. A KF Agent that does not send it
 *       (an older Part 1) cannot prove completeness, so the run is refused.</li>
 *   <li><b>Baseline + shrink.</b> The first page's count is the population this scan must cover. A later page reporting
 *       FEWER events than the highest seen means events were removed mid-scan, so the offset window may have shifted
 *       past rows: fatal. Growth (new events at the tail) is benign.</li>
 *   <li><b>Overlap / order.</b> The agent orders by (created, id). A row whose (created, source_id) is not strictly after
 *       the previous row's is an overlap or out-of-order row: it is still appended (idempotent) but NOT credited toward
 *       the baseline, so duplicates can never mask a gap.</li>
 *   <li><b>Termination.</b> The scan ends on an empty page or a page shorter than the page size.</li>
 *   <li><b>Completeness.</b> Distinct in-order rows must reach the baseline; otherwise the scan is incomplete.</li>
 * </ol>
 * A failed guard throws {@link IncompleteScanException} carrying the partial result. Rows already appended stay (they
 * are immutable events and a re-run skips them), but the run is reported as failed, never as a complete import.
 */
public final class SyslogEventImportService {

    /** One page fetch ({@code KfAgentClient::fetch} in production). */
    @FunctionalInterface
    public interface PageFetcher {
        KfAgentClient.Page fetch(String resource, int start, int limit);
    }

    /** The scan could not be confirmed complete; {@link #partial()} holds what was processed. */
    public static final class IncompleteScanException extends RuntimeException {
        private final ImportResult partial;

        IncompleteScanException(String message, ImportResult partial) {
            super(message);
            this.partial = partial;
        }

        public ImportResult partial() {
            return partial;
        }
    }

    private final PageFetcher fetcher;
    private final EntityWriter repository;
    private final int pageSize;
    private final ObjectMapper mapper = new ObjectMapper();

    private int baselineSourceCount = -1;
    private int highWaterSourceCount = -1;
    private int inOrderRows;
    private int overlapRows;

    public SyslogEventImportService(PageFetcher fetcher, EntityWriter repository, int pageSize) {
        this.fetcher = fetcher;
        this.repository = repository;
        this.pageSize = pageSize > 0 ? pageSize : 500;
    }

    public ImportResult run(Connection conn, String extractionRunId) throws SQLException {
        conn.setAutoCommit(true);
        repository.ensureSchemaAndTable(conn);
        ImportResult result = new ImportResult(repository.entity(), repository.targetTable());

        Instant lastCreated = null;
        String lastId = null;
        int start = 0;
        while (true) {
            KfAgentClient.Page page = fetcher.fetch(repository.resource(), start, pageSize);
            result.countPage();
            checkSourceCount(page.sourceCount(), start, result);
            JsonNode rows = parseArray(page.body());

            for (JsonNode row : rows) {
                result.countFetched(1);
                Instant created = instant(row, "created_at");
                String id = text(row, "source_id");
                if (after(created, id, lastCreated, lastId)) {
                    inOrderRows++;
                    lastCreated = created;
                    lastId = id;
                } else {
                    overlapRows++;
                }
                try {
                    switch (repository.upsert(conn, row, extractionRunId)) {
                        case INSERTED -> result.countInserted();
                        case SKIPPED -> result.countSkipped();
                        case UPDATED -> result.countUpdated();
                    }
                } catch (SQLException | RuntimeException e) {
                    result.countFailure(repository.ref(row), e.getMessage());
                }
            }
            if (rows.size() == 0 || rows.size() < pageSize) {
                break;
            }
            start += pageSize;
        }

        if (inOrderRows < baselineSourceCount) {
            throw new IncompleteScanException("Incomplete SyslogEvent scan: baseline source count "
                    + baselineSourceCount + ", distinct in-order rows read " + inOrderRows
                    + (overlapRows > 0 ? " (plus " + overlapRows + " overlapping/out-of-order)" : ""), result);
        }
        return result;
    }

    private void checkSourceCount(Integer count, int start, ImportResult result) {
        if (count == null || count < 0) {
            throw new IncompleteScanException("KF Agent /kfagent/" + repository.resource() + "?start=" + start
                    + " did not report " + KfAgentClient.SOURCE_COUNT_HEADER + "; completeness cannot be verified"
                    + " (update and restart the Part 1 KF Agent)", result);
        }
        if (baselineSourceCount < 0) {
            baselineSourceCount = count;
        }
        if (highWaterSourceCount >= 0 && count < highWaterSourceCount) {
            throw new IncompleteScanException("SyslogEvent source count shrank during paginated extraction: "
                    + highWaterSourceCount + " -> " + count + " at start=" + start
                    + " (events removed mid-scan; rows may have been skipped)", result);
        }
        highWaterSourceCount = Math.max(highWaterSourceCount, count);
    }

    /** True when (created, id) is strictly after the previous key in the agent's (created, id) order. */
    static boolean after(Instant created, String id, Instant lastCreated, String lastId) {
        if (lastId == null && lastCreated == null) {
            return true;
        }
        if (created == null || lastCreated == null) {
            return id != null && lastId != null && id.compareTo(lastId) > 0;
        }
        int c = created.compareTo(lastCreated);
        if (c != 0) {
            return c > 0;
        }
        return id != null && lastId != null && id.compareTo(lastId) > 0;
    }

    private JsonNode parseArray(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new KfAgentClient.KfAgentHttpException(
                    "KF Agent /kfagent/" + repository.resource() + " did not return JSON: " + e.getMessage());
        }
        if (root == null || !root.isArray()) {
            String note = (root != null && root.has("error")) ? (": " + root.get("error").asText()) : "";
            throw new KfAgentClient.KfAgentHttpException(
                    "KF Agent /kfagent/" + repository.resource() + " returned a non-array response" + note);
        }
        return root;
    }

    /** The first page's source count (the population the scan had to cover); -1 before the first page. */
    public int baselineSourceCount() {
        return baselineSourceCount;
    }

    /** The highest source count reported during the scan (growth = events appended while scanning). */
    public int highWaterSourceCount() {
        return highWaterSourceCount;
    }

    /** Distinct rows read in strictly increasing (created, id) order. */
    public int inOrderRows() {
        return inOrderRows;
    }

    /** Rows that repeated or went backwards in (created, id) order (appended idempotently, not credited). */
    public int overlapRows() {
        return overlapRows;
    }
}

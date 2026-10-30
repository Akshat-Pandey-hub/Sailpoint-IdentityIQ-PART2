package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.FetchOptions;
import com.keyforge.part2.http.KfAgentClient;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Import loop for a Part 1 SQL report into its keyed table ({@link SqlReportRepository}), the same way the entity
 * imports work:
 * <ul>
 *   <li><b>Full run</b> (no fetch options): page {@code start}/{@code limit} through the report (next start = start +
 *       rows received; a short or empty page is the end), writing each row as it arrives: INSERTED, UPDATED or SKIPPED
 *       by its uuid. Only one page is held in memory.</li>
 *   <li><b>Caller-scoped run</b> ({@code start}/{@code limit}, filters and/or {@code modifiedAfter}): exactly one Part 1
 *       request with those options, written the same way. Calling the same page again updates or skips its rows; it
 *       never adds them again.</li>
 *   <li><b>Duplicate key:</b> two rows of one run with the same key columns would overwrite each other, so the second
 *       is reported as a failed row instead of being written.</li>
 *   <li><b>Deletion:</b> only after a full run that read every page with no failed row, rows of this table that the
 *       report no longer returns are marked {@code is_deleted = true, deleted_at = now()} (never hard-deleted), within
 *       the {@code SWEEP_MAX_DELETE_PERCENT} safety limit ({@link SeenKeys}); a row that comes back is revived by its
 *       next write. A caller-scoped run never marks anything deleted.</li>
 * </ul>
 * The keys seen in the run are kept in a session temporary table, not in memory.
 */
public final class SqlReportImportService {

    /** One KF Agent request for a report with the given options ({@code KfAgentClient::fetchPage} in production). */
    @FunctionalInterface
    public interface Fetcher {
        String fetch(String resource, FetchOptions options);
    }

    /** The import counts plus what the deletion step did (or why it did not run). */
    public record Outcome(ImportResult result, SoftDeleteSweeper.Result sweep) { }

    private final Fetcher fetcher;
    private final SqlReportRepository repository;
    private final int pageSize;
    private final int maxDeletePercent;
    private final ObjectMapper mapper = new ObjectMapper();

    public SqlReportImportService(Fetcher fetcher, SqlReportRepository repository, int pageSize) {
        this(fetcher, repository, pageSize, EntityImportService.DEFAULT_MAX_DELETE_PERCENT);
    }

    public SqlReportImportService(Fetcher fetcher, SqlReportRepository repository, int pageSize, int maxDeletePercent) {
        this.fetcher = fetcher;
        this.repository = repository;
        this.pageSize = pageSize > 0 ? pageSize : 500;
        this.maxDeletePercent = maxDeletePercent;
    }

    public Outcome run(Connection conn, String extractionRunId, FetchOptions options) throws SQLException {
        FetchOptions scope = options == null ? FetchOptions.none() : options;
        conn.setAutoCommit(true);
        repository.ensureSchemaAndTable(conn);
        SeenKeys.reset(conn);

        ImportResult result = new ImportResult(repository.entity(), repository.targetTable());
        int seen = 0;
        if (!scope.isEmpty()) {
            seen += write(conn, read(scope), result, extractionRunId);
        } else {
            int start = 0;
            while (true) {
                JsonNode rows = read(new FetchOptions(start, pageSize, null, Map.of()));
                seen += write(conn, rows, result, extractionRunId);
                if (rows.size() < pageSize) {
                    break;
                }
                start += rows.size();
            }
        }

        SoftDeleteSweeper.Result sweep = scope.isEmpty() ? SeenKeys.markMissing(conn, repository.targetTable(),
                repository.spec().pkColumn(), result.failed(), 0, seen, maxDeletePercent) : null;
        SeenKeys.drop(conn);
        return new Outcome(result, sweep);
    }

    private JsonNode read(FetchOptions options) {
        return parseArray(fetcher.fetch(repository.resource(), options));
    }

    /** Writes one page; returns how many distinct keys it added to this run's seen set. */
    private int write(Connection conn, JsonNode rows, ImportResult result, String runId) {
        result.countPage();
        int added = 0;
        for (JsonNode row : rows) {
            result.countFetched(1);
            try {
                if (!SeenKeys.add(conn, repository.key(row))) {
                    result.countFailure(repository.ref(row), "duplicate report row key: another row of this run has "
                            + "the same " + repository.spec().keyColumns() + "; not written");
                    continue;
                }
                added++;
                switch (repository.upsert(conn, row, runId)) {
                    case INSERTED -> result.countInserted();
                    case UPDATED -> result.countUpdated();
                    case SKIPPED -> result.countSkipped();
                }
            } catch (SQLException | RuntimeException e) {
                result.countFailure(repository.ref(row), e.getMessage());
            }
        }
        return added;
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
}

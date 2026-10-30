package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.FetchOptions;
import com.keyforge.part2.http.KfAgentClient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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
 *       report no longer returns are marked {@code is_deleted = true, deleted_at = now()} (never hard-deleted); a row
 *       that comes back is revived by its next write. A caller-scoped run never marks anything deleted.</li>
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

    private static final String SEEN = "pg_temp.kf_sql_report_seen";

    private final Fetcher fetcher;
    private final SqlReportRepository repository;
    private final int pageSize;
    private final ObjectMapper mapper = new ObjectMapper();

    public SqlReportImportService(Fetcher fetcher, SqlReportRepository repository, int pageSize) {
        this.fetcher = fetcher;
        this.repository = repository;
        this.pageSize = pageSize > 0 ? pageSize : 500;
    }

    public Outcome run(Connection conn, String extractionRunId, FetchOptions options) throws SQLException {
        FetchOptions scope = options == null ? FetchOptions.none() : options;
        conn.setAutoCommit(true);
        repository.ensureSchemaAndTable(conn);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE IF NOT EXISTS kf_sql_report_seen (k uuid PRIMARY KEY)");
            st.execute("TRUNCATE " + SEEN);
        }

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

        SoftDeleteSweeper.Result sweep = scope.isEmpty() ? markMissing(conn, result, seen) : null;
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + SEEN);
        }
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
                if (!markSeen(conn, repository.key(row))) {
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

    /** Records a key as seen in this run; {@code false} when it was already seen (a duplicate). */
    private static boolean markSeen(Connection conn, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + SEEN + " (k) VALUES (?::uuid) ON CONFLICT DO NOTHING")) {
            ps.setString(1, key);
            return ps.executeUpdate() == 1;
        }
    }

    /** After a complete full run with no failure: soft-delete this table's rows the report did not return. */
    private SoftDeleteSweeper.Result markMissing(Connection conn, ImportResult result, int seen) throws SQLException {
        String table = repository.spec().table();
        if (result.failed() > 0) {
            return new SoftDeleteSweeper.Result(table, SoftDeleteSweeper.Status.SKIPPED_ROW_FAILURES, seen, 0, 0,
                    result.failed() + " row(s) failed; no row was marked deleted");
        }
        if (seen == 0) {
            return new SoftDeleteSweeper.Result(table, SoftDeleteSweeper.Status.SKIPPED_EMPTY_KEEP, 0, 0, 0,
                    "the report returned no rows; an empty result never marks every row deleted");
        }
        String pk = repository.spec().pkColumn();
        try (Statement st = conn.createStatement()) {
            int marked = st.executeUpdate("UPDATE " + repository.targetTable() + " AS t SET is_deleted = true, "
                    + "deleted_at = now() WHERE t.is_deleted = false AND NOT EXISTS (SELECT 1 FROM " + SEEN
                    + " s WHERE s.k = t." + pk + ")");
            return new SoftDeleteSweeper.Result(table, SoftDeleteSweeper.Status.SWEPT, seen, marked, 0,
                    "complete paged read of the report, every row written");
        }
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

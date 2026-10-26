package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.KfAgentClient;
import com.keyforge.part2.http.FetchOptions;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Orchestrates the Identity import: page {@code GET /kfagent/identities} (via {@code start}/{@code limit}),
 * parse each JSON-array page, and upsert every row through {@link IdentityRepository}. Per-row failures are
 * collected and the import continues (the connection runs auto-commit, so one bad row never poisons the
 * rest). Paging stops when a page returns fewer rows than the page size (or none) — i.e. the real end of the
 * endpoint's window, not an assumption that one request returns everything.
 *
 * <p><b>No deletion sweep:</b> this milestone never marks rows {@code is_deleted}; a row missing from a
 * (possibly partial/paged) pull is NOT treated as deleted. A safe full-snapshot sweep is deferred.
 */
public final class IdentityImportService {

    private static final String RESOURCE = "identities";

    private final KfAgentClient client;
    private final IdentityRepository repository;
    private final int pageSize;
    private final ObjectMapper mapper = new ObjectMapper();

    public IdentityImportService(KfAgentClient client, IdentityRepository repository, int pageSize) {
        this.client = client;
        this.repository = repository;
        this.pageSize = pageSize > 0 ? pageSize : 500;
    }

    public ImportResult run(Connection conn, String extractionRunId) throws SQLException {
        conn.setAutoCommit(true); // each upsert commits independently → a row error can't abort the batch
        repository.ensureSchemaAndTable(conn);

        ImportResult result = new ImportResult("Identity", repository.targetTable());
        int start = 0;
        while (true) {
            String body = client.fetchPage(RESOURCE, start, pageSize);
            JsonNode page = parseArray(body);
            result.countPage();

            int inPage = page.size();
            if (inPage == 0) {
                break;
            }
            for (JsonNode row : page) {
                result.countFetched(1);
                String ref = refOf(row);
                try {
                    IdentityRepository.Outcome o = repository.upsert(conn, row, extractionRunId);
                    if (o == IdentityRepository.Outcome.INSERTED) {
                        result.countInserted();
                    } else {
                        result.countUpdated();
                    }
                } catch (SQLException | RuntimeException e) {
                    result.countFailure(ref, e.getMessage());
                }
            }
            if (inPage < pageSize) {
                break; // last (short) page
            }
            start += pageSize;
        }
        return result;
    }

    /** Imports exactly the caller-selected source result; partial requests never continue paging or sweep. */
    public ImportResult run(Connection conn, String extractionRunId, FetchOptions options) throws SQLException {
        if (options == null || options.isEmpty()) return run(conn, extractionRunId);
        conn.setAutoCommit(true);
        repository.ensureSchemaAndTable(conn);
        ImportResult result = new ImportResult("Identity", repository.targetTable());
        JsonNode page = parseArray(client.fetch(RESOURCE, options).body());
        result.countPage();
        for (JsonNode row : page) {
            result.countFetched(1);
            try {
                if (repository.upsert(conn, row, extractionRunId) == IdentityRepository.Outcome.INSERTED) {
                    result.countInserted();
                } else {
                    result.countUpdated();
                }
            } catch (SQLException | RuntimeException e) {
                result.countFailure(refOf(row), e.getMessage());
            }
        }
        return result;
    }

    private JsonNode parseArray(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new KfAgentClient.KfAgentHttpException(
                    "KF Agent /kfagent/identities did not return JSON: " + e.getMessage());
        }
        if (root == null || !root.isArray()) {
            // The endpoint returns a bare JSON array; an object here is an error/unknown shape.
            String note = (root != null && root.has("error")) ? (": " + root.get("error").asText()) : "";
            throw new KfAgentClient.KfAgentHttpException(
                    "KF Agent /kfagent/identities returned a non-array response" + note);
        }
        return root;
    }

    private static String refOf(JsonNode row) {
        JsonNode id = row.get("source_id");
        if (id != null && !id.isNull()) {
            return id.asText();
        }
        JsonNode name = row.get("name");
        return (name != null && !name.isNull()) ? name.asText() : "<no source_id>";
    }
}

package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.FetchOptions;
import com.keyforge.part2.http.KfAgentClient;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fetches and durably records exactly one caller-selected SQL-report page in the append-only snapshot tables. */
public final class SqlReportPageSnapshotImportService {

    @FunctionalInterface
    public interface PageFetcher {
        String fetchPage(String resource, FetchOptions options);
    }

    private final PageFetcher fetcher;
    private final SqlReportPageSnapshotRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();
    private UUID lastSnapshotId;

    public SqlReportPageSnapshotImportService(PageFetcher fetcher, SqlReportPageSnapshotRepository repository) {
        this.fetcher = fetcher;
        this.repository = repository;
    }

    public ImportResult run(Connection conn, String reportName, String resource, String extractionRunId,
                            FetchOptions options) throws SQLException {
        if (options == null || !options.hasWindow() || options.limit() == null || options.modifiedAfter() != null
                || !options.filters().isEmpty()) {
            throw new IllegalArgumentException("SQL report page snapshots require only start and limit");
        }

        // Fetch and validate the source response before any database changes.
        JsonNode page = parseArray(fetcher.fetchPage(resource, options));
        if (page.size() > options.limit()) {
            throw new KfAgentClient.KfAgentHttpException("KF Agent SQL report returned " + page.size()
                    + " rows for a requested limit of " + options.limit());
        }
        List<JsonNode> rows = new ArrayList<>(page.size());
        page.forEach(row -> {
            if (!row.isObject()) {
                throw new KfAgentClient.KfAgentHttpException("KF Agent SQL report returned a non-object row");
            }
            rows.add(row);
        });

        repository.ensureSchemaAndTables(conn);
        SqlReportPageSnapshotRepository.StoredSnapshot stored = repository.store(conn, reportName, resource,
                extractionRunId, options.start(), options.limit(), rows);
        lastSnapshotId = stored.id();

        ImportResult result = new ImportResult(reportName, repository.rowTable());
        result.countPage();
        result.countFetched(stored.rowsStored());
        for (int i = 0; i < stored.rowsStored(); i++) result.countInserted();
        return result;
    }

    public UUID lastSnapshotId() {
        return lastSnapshotId;
    }

    private JsonNode parseArray(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new KfAgentClient.KfAgentHttpException(
                    "KF Agent SQL report did not return JSON: " + e.getMessage());
        }
        if (root == null || !root.isArray()) {
            String note = root != null && root.has("error") ? (": " + root.get("error").asText()) : "";
            throw new KfAgentClient.KfAgentHttpException("KF Agent SQL report returned a non-array response" + note);
        }
        return root;
    }
}

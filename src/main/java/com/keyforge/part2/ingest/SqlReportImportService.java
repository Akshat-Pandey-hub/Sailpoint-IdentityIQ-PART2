package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.FetchOptions;
import com.keyforge.part2.http.KfAgentClient;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Import loop for a SQL-report endpoint (full snapshot, replace-all). Unlike {@link EntityImportService} it first
 * fetches EVERY page ({@code start}/{@code limit} until an empty or short page) and only then replaces the table in one
 * transaction via {@link SqlReportRepository#replaceAll}. So an HTTP / parse failure part-way through never deletes the
 * previous snapshot, and a database failure rolls back to it.
 */
public final class SqlReportImportService {

    /** One page of a KF Agent resource as raw JSON ({@code KfAgentClient::fetchPage} in production). */
    @FunctionalInterface
    public interface PageFetcher {
        String fetchPage(String resource, int start, int limit);
    }

    private final PageFetcher fetcher;
    private final String resource;
    private final SqlReportRepository repository;
    private final int pageSize;
    private final ObjectMapper mapper = new ObjectMapper();
    private int lastDeleted;

    public SqlReportImportService(PageFetcher fetcher, String resource, SqlReportRepository repository, int pageSize) {
        this.fetcher = fetcher;
        this.resource = resource;
        this.repository = repository;
        this.pageSize = pageSize > 0 ? pageSize : 500;
    }

    public ImportResult run(Connection conn, String entity, String extractionRunId) throws SQLException {
        ImportResult result = new ImportResult(entity, repository.targetTable());
        List<JsonNode> all = new ArrayList<>();
        int start = 0;
        while (true) {
            JsonNode page = parseArray(fetcher.fetchPage(resource, start, pageSize));
            result.countPage();
            int inPage = page.size();
            for (JsonNode row : page) {
                all.add(row);
            }
            if (inPage == 0 || inPage < pageSize) {
                break;
            }
            start += pageSize;
        }
        result.countFetched(all.size());

        repository.ensureSchemaAndTable(conn);
        SqlReportRepository.Replaced replaced = repository.replaceAll(conn, all, extractionRunId);
        for (int i = 0; i < replaced.inserted(); i++) {
            result.countInserted();
        }
        lastDeleted = replaced.deleted();
        return result;
    }

    /** SQL-report replacement is valid only for a complete, unscoped source snapshot. */
    public ImportResult run(Connection conn, String entity, String extractionRunId, FetchOptions options)
            throws SQLException {
        if (options != null && !options.isEmpty()) {
            throw new IllegalArgumentException("SQL-report replacement cannot run for a caller-scoped import");
        }
        return run(conn, entity, extractionRunId);
    }

    /** Rows removed by the last {@link #run} (the previous snapshot). */
    public int lastDeleted() {
        return lastDeleted;
    }

    private JsonNode parseArray(String body) {
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

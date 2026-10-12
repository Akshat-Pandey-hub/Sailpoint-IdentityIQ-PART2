package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.KfAgentClient;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Generic import loop for one {@link EntityWriter}: page {@code GET /kfagent/<resource>?start=&limit=}, parse
 * each bare JSON-array page, upsert every row through the writer. Same behavior as the
 * Identity milestone: auto-commit per row (one bad row never aborts the rest), per-row failures collected,
 * paging stops on an empty or short page. No deletion sweep — a row missing from a pull is NOT deleted.
 */
public final class EntityImportService {

    private final KfAgentClient client;
    private final EntityWriter repository;
    private final int pageSize;
    private final ObjectMapper mapper = new ObjectMapper();

    public EntityImportService(KfAgentClient client, EntityWriter repository, int pageSize) {
        this.client = client;
        this.repository = repository;
        this.pageSize = pageSize > 0 ? pageSize : 500;
    }

    public ImportResult run(Connection conn, String extractionRunId) throws SQLException {
        conn.setAutoCommit(true);
        repository.ensureSchemaAndTable(conn);

        ImportResult result = new ImportResult(repository.entity(), repository.targetTable());
        int start = 0;
        while (true) {
            JsonNode page = parseArray(repository.resource(), client.fetchPage(repository.resource(), start, pageSize));
            result.countPage();

            int inPage = page.size();
            if (inPage == 0) {
                break;
            }
            for (JsonNode row : page) {
                result.countFetched(1);
                try {
                    switch (repository.upsert(conn, row, extractionRunId)) {
                        case INSERTED -> result.countInserted();
                        case UPDATED -> result.countUpdated();
                        case SKIPPED -> result.countSkipped();
                    }
                } catch (SQLException | RuntimeException e) {
                    result.countFailure(repository.ref(row), e.getMessage());
                }
            }
            if (inPage < pageSize) {
                break;
            }
            start += pageSize;
        }
        return result;
    }

    private JsonNode parseArray(String resource, String body) {
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

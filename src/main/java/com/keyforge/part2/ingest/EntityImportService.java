package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.http.KfAgentClient;
import com.keyforge.part2.http.FetchOptions;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Generic import loop for one {@link EntityWriter}: page {@code GET /kfagent/<resource>?start=&limit=}, parse
 * each bare JSON-array page, upsert every row through the writer. Auto-commit per row (one bad row never aborts the
 * rest), per-row failures collected, paging stops on an empty or short page (next start = start + rows received).
 *
 * <p>A full run (no fetch options) of a table that exposes its key ({@link EntityWriter#keyColumn()}) and is not
 * append-only also records every key it receives, and afterwards marks the table's live rows that the source no
 * longer returned as deleted ({@link SeenKeys}). A caller-scoped run (window, filters, {@code modifiedAfter}) never
 * marks anything deleted.
 */
public final class EntityImportService {

    /** Default {@code SWEEP_MAX_DELETE_PERCENT}. */
    public static final int DEFAULT_MAX_DELETE_PERCENT = 50;

    private final KfAgentClient client;
    private final EntityWriter repository;
    private final int pageSize;
    private final int maxDeletePercent;
    private final ObjectMapper mapper = new ObjectMapper();
    private SoftDeleteSweeper.Result lastSweep;

    public EntityImportService(KfAgentClient client, EntityWriter repository, int pageSize) {
        this(client, repository, pageSize, DEFAULT_MAX_DELETE_PERCENT);
    }

    public EntityImportService(KfAgentClient client, EntityWriter repository, int pageSize, int maxDeletePercent) {
        this.client = client;
        this.repository = repository;
        this.pageSize = pageSize > 0 ? pageSize : 500;
        this.maxDeletePercent = maxDeletePercent;
    }

    /** True when a full run of this writer's table marks rows the source no longer returns. */
    public static boolean marksDeletions(EntityWriter writer) {
        String table = writer.targetTable();
        return writer.keyColumn() != null
                && !SweepPolicies.isNeverSweepTable(table.substring(table.lastIndexOf('.') + 1));
    }

    /** What the last full run's deletion marking did, or {@code null} when it does not apply to this table/run. */
    public SoftDeleteSweeper.Result lastSweep() {
        return lastSweep;
    }

    public ImportResult run(Connection conn, String extractionRunId) throws SQLException {
        conn.setAutoCommit(true);
        repository.ensureSchemaAndTable(conn);
        lastSweep = null;
        boolean track = marksDeletions(repository);
        if (track) {
            SeenKeys.reset(conn);
        }

        ImportResult result = new ImportResult(repository.entity(), repository.targetTable());
        int seen = 0;
        int unkeyed = 0;
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
                    if (track) {
                        String key = keyOf(row);
                        if (key == null) {
                            unkeyed++;
                        } else if (SeenKeys.add(conn, key)) {
                            seen++;
                        }
                    }
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
            start += inPage; // Part 1: next start = start + rows received
        }
        if (track) {
            lastSweep = SeenKeys.markMissing(conn, repository.targetTable(), repository.keyColumn(), result.failed(),
                    unkeyed, seen, maxDeletePercent);
            SeenKeys.drop(conn);
        }
        return result;
    }

    private String keyOf(JsonNode row) {
        try {
            return repository.key(row);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Imports exactly the caller-selected source result; partial requests never continue paging or sweep. */
    public ImportResult run(Connection conn, String extractionRunId, FetchOptions options) throws SQLException {
        if (options == null || options.isEmpty()) return run(conn, extractionRunId);
        lastSweep = null;
        conn.setAutoCommit(true);
        repository.ensureSchemaAndTable(conn);
        ImportResult result = new ImportResult(repository.entity(), repository.targetTable());
        JsonNode page = parseArray(repository.resource(), client.fetch(repository.resource(), options).body());
        result.countPage();
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

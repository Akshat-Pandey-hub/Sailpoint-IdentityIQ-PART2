package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * What {@link EntityImportService} needs to load one KF Agent resource into one table. {@link EntityRepository}
 * covers the regular tables from an {@link EntitySpec}; a table whose Part 1 persistence derives columns the
 * response does not carry (e.g. kf_role_entitlement) gets its own implementation.
 */
public interface EntityWriter {

    /** Label for logs/summary. */
    String entity();

    /** KF Agent path under {@code /kfagent/}. */
    String resource();

    /** Schema-qualified destination table. */
    String targetTable();

    /** Create the destination schema/table if absent and check any preconditions. */
    void ensureSchemaAndTable(Connection conn) throws SQLException;

    /** Insert or update one response row. */
    EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException;

    /** Short human reference for failure messages. */
    String ref(JsonNode row);
}

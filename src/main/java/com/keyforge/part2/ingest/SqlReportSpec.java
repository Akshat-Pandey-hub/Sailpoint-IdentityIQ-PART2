package com.keyforge.part2.ingest;

import java.util.List;

/**
 * Everything {@link SqlReportRepository} needs to store one Part 1 SQL report as a keyed current-state table.
 *
 * @param entity     label for logs/summary, e.g. {@code "EntitlementAssignment"}
 * @param reportId   Part 1's registered report id, e.g. {@code "entitlementAssignment"} (part of the row key seed and of
 *                   {@code source_object_type})
 * @param resource   KF Agent path under {@code /kfagent/}, e.g. {@code "entitlement-assignment"}
 * @param table      destination table name (unqualified)
 * @param pkColumn   uuid primary-key column
 * @param columns    the report's SELECT aliases, in order (stored as text, exactly as returned)
 * @param keyColumns the columns that identify one report row: the row's uuid is derived from their values, so the same
 *                   report row always maps to the same uuid and a repeated request updates or skips it
 */
public record SqlReportSpec(String entity, String reportId, String resource, String table, String pkColumn,
                            List<String> columns, List<String> keyColumns) {

    public SqlReportSpec {
        columns = List.copyOf(columns);
        keyColumns = List.copyOf(keyColumns);
        if (!columns.containsAll(keyColumns) || keyColumns.isEmpty()) {
            throw new IllegalArgumentException(table + ": key columns must be a non-empty subset of the report columns");
        }
    }

    /** {@code source_object_type} stamped on every row, e.g. {@code sql_report.entitlementAssignment}. */
    public String sourceObjectType() {
        return "sql_report." + reportId;
    }
}

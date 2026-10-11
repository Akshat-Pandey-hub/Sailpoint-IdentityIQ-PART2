package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Function;

/**
 * Everything the generic engine ({@link EntityRepository} + {@link EntityImportService}) needs to ingest one
 * KF Agent resource into one current-state table. Each entity declares its own key derivation, hash
 * function and lineage constants (verified against its Part 1 repository) — nothing is inherited from
 * Identity by default.
 *
 * @param entity           label for logs/summary, e.g. {@code "Entitlement"}
 * @param resource         KF Agent path under {@code /kfagent/}, e.g. {@code "entitlements"}
 * @param table            destination table name (unqualified)
 * @param pkColumn         uuid primary-key / conflict column
 * @param business         response-provided columns, in table order; names = response keys
 * @param keyFn            JSON row → canonical uuid string for {@code pkColumn} (deterministic, never random)
 * @param recordHashFn     JSON row → {@code record_hash}, or {@code null} when the table has no record_hash
 * @param refFn            JSON row → short human reference for failure messages
 * @param sourceSystem     {@code source_system} value
 * @param sourceInterface  {@code source_interface} value
 * @param sourceObjectType {@code source_object_type} value
 */
public record EntitySpec(
        String entity,
        String resource,
        String table,
        String pkColumn,
        List<ColumnSpec> business,
        Function<JsonNode, String> keyFn,
        Function<JsonNode, String> recordHashFn,
        Function<JsonNode, String> refFn,
        String sourceSystem,
        String sourceInterface,
        String sourceObjectType) {

    public boolean hasRecordHash() {
        return recordHashFn != null;
    }
}

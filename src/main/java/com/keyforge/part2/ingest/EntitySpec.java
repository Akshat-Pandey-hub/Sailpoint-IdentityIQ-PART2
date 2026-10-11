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
 * @param trailing         further response-provided columns that iiq_native places AFTER {@code deleted_at}
 *                         (Part 1 added them later with {@code ALTER TABLE … ADD COLUMN}); empty for most tables.
 *                         They are bound, hashed and returned like {@code business}; only the DDL position differs.
 * @param objectTypeFn     optional per-row {@code source_object_type} (for tables where Part 1 stamps a different
 *                         object type per row, e.g. account-entitlement edge kinds); {@code null} = always
 *                         {@code sourceObjectType}
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
        String sourceObjectType,
        List<ColumnSpec> trailing,
        Function<JsonNode, String> objectTypeFn) {

    /** Spec for a table whose business columns all precede the lineage columns (no trailing columns). */
    public EntitySpec(String entity, String resource, String table, String pkColumn, List<ColumnSpec> business,
                      Function<JsonNode, String> keyFn, Function<JsonNode, String> recordHashFn,
                      Function<JsonNode, String> refFn, String sourceSystem, String sourceInterface,
                      String sourceObjectType) {
        this(entity, resource, table, pkColumn, business, keyFn, recordHashFn, refFn,
                sourceSystem, sourceInterface, sourceObjectType, List.of(), null);
    }

    /** Spec with trailing columns and a constant {@code source_object_type}. */
    public EntitySpec(String entity, String resource, String table, String pkColumn, List<ColumnSpec> business,
                      Function<JsonNode, String> keyFn, Function<JsonNode, String> recordHashFn,
                      Function<JsonNode, String> refFn, String sourceSystem, String sourceInterface,
                      String sourceObjectType, List<ColumnSpec> trailing) {
        this(entity, resource, table, pkColumn, business, keyFn, recordHashFn, refFn,
                sourceSystem, sourceInterface, sourceObjectType, trailing, null);
    }

    /** The {@code source_object_type} to stamp on this row. */
    public String sourceObjectTypeFor(JsonNode row) {
        return objectTypeFn == null ? sourceObjectType : objectTypeFn.apply(row);
    }

    /** Every response-provided column: {@code business} then {@code trailing} (= response field order). */
    public List<ColumnSpec> allBusiness() {
        if (trailing.isEmpty()) {
            return business;
        }
        List<ColumnSpec> all = new java.util.ArrayList<>(business);
        all.addAll(trailing);
        return List.copyOf(all);
    }

    public boolean hasRecordHash() {
        return recordHashFn != null;
    }
}

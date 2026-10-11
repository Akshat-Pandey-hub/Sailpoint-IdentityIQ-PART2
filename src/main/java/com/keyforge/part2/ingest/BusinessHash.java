package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.hash.RecordHash;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.bool;
import static com.keyforge.part2.hash.HashValues.instant;
import static com.keyforge.part2.hash.HashValues.integer;
import static com.keyforge.part2.hash.HashValues.json;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code record_hash} for entities whose Part 1 {@code recordHash} covers EVERY business column (column name =
 * hash key), each read with the Java type Part 1's parser produced for its kind. Only use it after confirming
 * the Part 1 hash field list equals the business column list (Entitlement, for one, hashes a subset).
 */
final class BusinessHash {

    private BusinessHash() {
    }

    static String allColumns(JsonNode row, List<ColumnSpec> business) {
        Map<String, Object> b = new LinkedHashMap<>();
        for (ColumnSpec c : business) {
            b.put(c.name, switch (c.kind) {
                case TEXT -> text(row, c.name);
                case BOOLEAN -> bool(row, c.name);
                case INTEGER -> integer(row, c.name);
                case JSONB -> json(row, c.name);
                case TIMESTAMPTZ -> instant(row, c.name);
            });
        }
        return RecordHash.of(b);
    }
}

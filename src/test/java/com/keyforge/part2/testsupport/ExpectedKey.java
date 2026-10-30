package com.keyforge.part2.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

/** The uuid Part 2 gives a golden row: the id-based key when the row carries an IIQ id, else its content uuid. */
public final class ExpectedKey {

    private ExpectedKey() {
    }

    public static String of(String idBasedKey, JsonNode row, String prefix) {
        JsonNode n = row.get("source_id");
        boolean noId = n == null || n.isNull() || n.asText().isBlank();
        return noId ? CanonicalIds.contentUuid(prefix, row) : idBasedKey;
    }
}

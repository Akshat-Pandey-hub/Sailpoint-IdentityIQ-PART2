package com.keyforge.part2.hash;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Reads a KF Agent JSON field into the SAME Java value Part 1's native parsers produced before hashing,
 * so {@link RecordHash} over a Part 2 row equals Part 1's {@code record_hash} for the same source object:
 * <ul>
 *   <li>text → {@code asText()}</li>
 *   <li>boolean → {@code Boolean.valueOf(node.asBoolean())}</li>
 *   <li>integer → {@code Integer.valueOf(node.asInt())}</li>
 *   <li>nested JSON → compact Jackson {@code toString()}</li>
 *   <li>timestamp → {@link Instant#parse} (unparseable → null), hashed via {@code Instant.toString()}</li>
 * </ul>
 * Absent or JSON-null → {@code null} for all kinds.
 */
public final class HashValues {

    private HashValues() {
    }

    public static String text(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    public static Boolean bool(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return (n == null || n.isNull()) ? null : Boolean.valueOf(n.asBoolean());
    }

    public static Integer integer(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return (n == null || n.isNull()) ? null : Integer.valueOf(n.asInt());
    }

    public static String json(JsonNode row, String field) {
        JsonNode n = row.get(field);
        return (n == null || n.isNull()) ? null : n.toString();
    }

    public static Instant instant(JsonNode row, String field) {
        String s = text(row, field);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

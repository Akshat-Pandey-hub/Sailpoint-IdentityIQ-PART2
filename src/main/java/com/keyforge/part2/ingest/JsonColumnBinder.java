package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Binds one JSON value to a {@link PreparedStatement} parameter according to its {@link ColumnSpec.Kind}
 * (same rules as the Identity milestone): absent/null → SQL NULL; text verbatim; booleans as booleans
 * (textual "true"/"false" tolerated, anything else NULL); integers as int (an integral number or numeric text;
 * anything else fails the row rather than storing a wrong value); nested JSON as its compact string for
 * {@code ?::jsonb}; ISO-8601 strings for {@code ?::timestamptz}; JSON_TEXT as compact JSON text (plain strings verbatim).
 */
final class JsonColumnBinder {

    private JsonColumnBinder() {
    }

    static void bind(PreparedStatement ps, int index, ColumnSpec c, JsonNode node) throws SQLException {
        boolean isNull = node == null || node.isNull();
        switch (c.kind) {
            case TEXT -> {
                if (isNull) {
                    ps.setNull(index, Types.VARCHAR);
                } else {
                    ps.setString(index, node.asText());
                }
            }
            case BOOLEAN -> {
                if (isNull) {
                    ps.setNull(index, Types.BOOLEAN);
                } else if (node.isBoolean()) {
                    ps.setBoolean(index, node.booleanValue());
                } else {
                    String s = node.asText();
                    if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
                        ps.setBoolean(index, Boolean.parseBoolean(s));
                    } else {
                        ps.setNull(index, Types.BOOLEAN);
                    }
                }
            }
            case INTEGER -> {
                if (isNull) {
                    ps.setNull(index, Types.INTEGER);
                } else if (node.isIntegralNumber() && node.canConvertToInt()) {
                    ps.setInt(index, node.intValue());
                } else {
                    try {
                        ps.setInt(index, Integer.parseInt(node.asText().trim()));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("column " + c.name + " expects an integer but got: " + node);
                    }
                }
            }
            case JSON_TEXT -> {
                if (isNull) {
                    ps.setNull(index, Types.VARCHAR);
                } else {
                    ps.setString(index, node.isTextual() ? node.asText() : node.toString());
                }
            }
            case JSONB, TIMESTAMPTZ -> {
                if (isNull) {
                    ps.setNull(index, Types.VARCHAR);
                } else {
                    ps.setString(index, c.kind == ColumnSpec.Kind.JSONB ? node.toString() : node.asText());
                }
            }
            default -> throw new IllegalStateException("unhandled column kind: " + c.kind);
        }
    }
}

package com.keyforge.part2.config;

/**
 * Validates a PostgreSQL schema identifier before it is interpolated into DDL/SQL (schema names cannot be
 * bound as JDBC parameters). Allows only {@code [A-Za-z_][A-Za-z0-9_]{0,62}} — no quotes, dots, spaces or
 * other characters — so a configured schema name can never inject SQL. Mirrors Part 1's SchemaName guard.
 */
public final class SchemaName {

    public static final String DEFAULT = "iiq_native_part2";

    private SchemaName() {
    }

    public static String validate(String schema) {
        if (schema == null || schema.isBlank()) {
            throw new ConfigException("Destination schema name must not be blank");
        }
        String s = schema.trim();
        if (!s.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new ConfigException("Invalid destination schema name '" + s
                    + "': only letters, digits and underscore are allowed (must start with a letter/underscore).");
        }
        return s;
    }
}

package com.keyforge.part2.ingest;

/**
 * One destination column: its name and its PostgreSQL kind, which drives both the DDL type, the INSERT
 * placeholder cast, and how a JSON value is bound. Keeping this as data (not hand-written SQL) lets the
 * same upsert engine serve further entities later.
 */
public final class ColumnSpec {

    /** PostgreSQL column kinds Part 2 handles. */
    public enum Kind {
        TEXT("text", "?"),
        BOOLEAN("boolean", "?"),
        JSONB("jsonb", "?::jsonb"),
        TIMESTAMPTZ("timestamp with time zone", "?::timestamptz");

        public final String ddlType;
        public final String placeholder;

        Kind(String ddlType, String placeholder) {
            this.ddlType = ddlType;
            this.placeholder = placeholder;
        }
    }

    public final String name;
    public final Kind kind;

    private ColumnSpec(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
    }

    public static ColumnSpec text(String name) { return new ColumnSpec(name, Kind.TEXT); }
    public static ColumnSpec bool(String name) { return new ColumnSpec(name, Kind.BOOLEAN); }
    public static ColumnSpec jsonb(String name) { return new ColumnSpec(name, Kind.JSONB); }
    public static ColumnSpec timestamptz(String name) { return new ColumnSpec(name, Kind.TIMESTAMPTZ); }
}

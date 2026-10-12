-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_syslog_event (IIQ SyslogEvent, APPEND-ONLY).
--
-- Mirrors Part 1's NativeSyslogEventRepository CREATE TABLE exactly: syslogid uuid PK, the 11 SyslogEvent fields
-- (all text except created_at; classname between thread and line_number), record_hash, lineage. No soft-delete
-- columns. (Part 1's older iiq_native table predates classname; Part 1 appends it with ADD COLUMN IF NOT EXISTS.)
-- The application ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_syslog_event.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_syslog_event (
    syslogid uuid PRIMARY KEY,
    source_id text,
    quick_key text,
    event_level text,
    server text,
    username text,
    thread text,
    classname text,
    line_number text,
    message text,
    stacktrace text,
    created_at timestamp with time zone,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now()
);
-- Load is INSERT … ON CONFLICT (syslogid) DO NOTHING: events are never updated or deleted.

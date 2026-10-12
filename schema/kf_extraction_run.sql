-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_extraction_run (the run ledger: one row per Part 2 import command).
--
-- Same DDL as Part 1's iiq_native.kf_extraction_run. Owned by Part 2 (a record of Part 2's own runs, not fetched
-- from the KF Agent). extraction_run_id equals the extraction_run_id stamped on every row the run wrote.
-- status: SUCCESS | COMPLETED_WITH_FAILURES | INCOMPLETE (exit 7: completeness not proven) | FAILED.
-- entity_counts: per table {entity, extracted, inserted, updated, failed, skipped[, sweep, marked_deleted, revived]}.
-- The application ALSO creates it automatically after the first import.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_extraction_run.sql
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_extraction_run (
    extraction_run_id uuid PRIMARY KEY,
    command text NOT NULL,
    source_interface text,
    status text NOT NULL,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    duration_ms bigint,
    window_start timestamptz,
    window_end timestamptz,
    extracted integer,
    inserted integer,
    updated integer,
    failed integer,
    entity_counts jsonb,
    error_message text,
    recorded_at timestamptz NOT NULL DEFAULT now()
);

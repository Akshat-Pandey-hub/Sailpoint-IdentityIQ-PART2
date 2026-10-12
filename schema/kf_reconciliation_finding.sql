-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_reconciliation_finding (reconciliation results).
--
-- Same table as Part 1's iiq_native.kf_reconciliation_finding (column order included). Owned by Part 2: rows are
-- COMPUTED by reconcile-* commands from tables already loaded into this schema (never fetched from the KF Agent).
-- One finding per (run, check): finding_id = UUID.nameUUIDFromBytes(run_id || '|' || check_name), so a re-run of
-- the same run updates its finding and every new run adds its own dated finding. run_id = kf_extraction_run id.
-- status CHECKED | SKIPPED; orphan_count NULL when SKIPPED; severity per check (approval_workitem_reference:
-- OK | INFO | SKIPPED); note = neutral determination text. The application ALSO creates it on the first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_reconciliation_finding.sql
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_reconciliation_finding (
    finding_id uuid PRIMARY KEY,
    run_id text,
    check_name text,
    child_table text,
    child_column text,
    parent_table text,
    parent_column text,
    status text,
    skip_reason text,
    orphan_count bigint,
    sample_ids jsonb,
    severity text,
    detected_at timestamptz,
    note text
);

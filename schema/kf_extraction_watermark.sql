-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_extraction_watermark (incremental-import watermarks).
--
-- Same DDL and keys as Part 1's iiq_native.kf_extraction_watermark: one row per entity key 'native:<table>'
-- (native:kf_identity, native:kf_entitlement, native:kf_application, native:kf_account, native:kf_role),
-- watermark_field 'modified', source_watermark = max(modified_at) captured by the last fully successful
-- incremental run, last_run_id = that run's extraction_run_id (joins kf_extraction_run). Owned by Part 2.
-- Part 2 only ever moves source_watermark FORWARD. The application ALSO creates it on the first incremental run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_extraction_watermark.sql
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_extraction_watermark (
    entity text PRIMARY KEY,
    watermark_field text,
    source_watermark timestamptz,
    last_run_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now()
);

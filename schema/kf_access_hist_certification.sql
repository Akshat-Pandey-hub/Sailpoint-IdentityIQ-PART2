-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_access_hist_certification (IIQ Access-History certifications,
-- APPEND-ONLY).
--
-- Mirrors Part 1's iiq_native.kf_access_hist_certification exactly: hist_cert_id uuid PK, the 11 fields (finished /
-- signed / created_at / modified_at timestamps, cert_json TEXT), record_hash, lineage. No soft-delete columns. The
-- application ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_access_hist_certification.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_access_hist_certification (
    hist_cert_id uuid PRIMARY KEY,
    source_id text,
    name text,
    cert_id text,
    cert_type text,
    cert_name text,
    cert_display_name text,
    finished timestamp with time zone,
    signed timestamp with time zone,
    cert_json text,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now()
);
-- Load is INSERT … ON CONFLICT (hist_cert_id) DO NOTHING: rows are never updated or deleted.

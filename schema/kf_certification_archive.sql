-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_certification_archive (IIQ CertificationArchive, append-only history).
--
-- Mirrors Part 1's iiq_native.kf_certification_archive exactly (columns, types, order, PK; CEC lineage envelope,
-- no is_deleted/deleted_at). The application ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_certification_archive.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_certification_archive (
    certificationarchiveid uuid PRIMARY KEY,
    source_id text,
    name text,
    certification_id text,
    certification_group_id text,
    creator_name text,
    owner_name text,
    comments text,
    signed timestamp with time zone,
    expiration timestamp with time zone,
    child_certification_ids jsonb,
    archive_xml text,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    archived_ts timestamp with time zone,
    record_hash text,
    src_system text,
    src_object_type text,
    src_object_id text,
    src_natural_key text,
    src_created timestamp with time zone,
    src_modified timestamp with time zone,
    src_event_ts timestamp with time zone,
    src_interface text,
    extraction_run_id text,
    raw_ref text,
    derived_at timestamp with time zone,
    extracted_at timestamp with time zone NOT NULL DEFAULT now()
);
-- Append-only: rows are inserted once (ON CONFLICT DO NOTHING) and never updated or deleted.

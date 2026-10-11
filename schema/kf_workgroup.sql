-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_workgroup.
--
-- Mirrors Part 1's iiq_native.kf_workgroup exactly (columns, types, order, PK). The application ALSO creates
-- the schema + table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is
-- optional — provided for a manual, reviewable, repeatable init.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_workgroup.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_workgroup (
    workgroupid uuid PRIMARY KEY,
    source_id text,
    name text,
    display_name text,
    displayable_name text,
    email text,
    type text,
    description text,
    notification_option text,
    inactive boolean,
    is_workgroup boolean,
    owner_id text,
    owner_name text,
    capabilities jsonb,
    attributes jsonb,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);

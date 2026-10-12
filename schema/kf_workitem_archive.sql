-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_workitem_archive (IIQ WorkItemArchive, APPEND-ONLY CEC table).
--
-- Mirrors Part 1's iiq_native.kf_workitem_archive exactly: archiveid uuid PK, the 29 archive fields (is_signed
-- boolean, 5 jsonb, 4 timestamps), record_hash, and the CEC src_* lineage envelope (no is_deleted / deleted_at).
-- The application ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_workitem_archive.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_workitem_archive (
    archiveid uuid PRIMARY KEY,
    source_id text,
    work_item_id text,
    name text,
    type text,
    state text,
    level text,
    requester text,
    assignee text,
    owner_name text,
    completer text,
    completion_comments text,
    is_signed boolean,
    target_class text,
    target_id text,
    target_name text,
    identity_request_id text,
    certification_id text,
    certification_entity_id text,
    certification_item_id text,
    entity_type text,
    signoffs jsonb,
    comments jsonb,
    owner_history jsonb,
    system_attributes jsonb,
    attributes jsonb,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    expiration_ts timestamp with time zone,
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
-- Load is INSERT … ON CONFLICT (archiveid) DO NOTHING: archives are never updated or deleted.

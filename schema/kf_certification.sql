-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_certification (IIQ Certification headers).
--
-- Mirrors Part 1's iiq_native.kf_certification exactly (columns, types, order, PK), including the 4 columns Part 1
-- appended after deleted_at. The application ALSO creates it automatically on first run, so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_certification.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_certification (
    certificationid uuid PRIMARY KEY,
    source_id text,
    name text,
    certification_name text,
    short_name text,
    type text,
    phase text,
    comments text,
    creator text,
    manager text,
    certification_group_id text,
    certification_group_name text,
    certification_definition_id text,
    group_definition_id text,
    group_definition_name text,
    application_id text,
    task_schedule_id text,
    trigger_id text,
    parent_id text,
    complete boolean,
    expired boolean,
    continuous boolean,
    electronically_signed boolean,
    signed timestamp with time zone,
    finished timestamp with time zone,
    activated timestamp with time zone,
    expiration timestamp with time zone,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    total_items integer,
    completed_items integer,
    open_items integer,
    total_entities integer,
    completed_entities integer,
    open_entities integer,
    percent_complete integer,
    certifiers jsonb,
    sign_off_history jsonb,
    owner_id text,
    owner_name text,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone,
    approver_rule text,
    automatic_closing_date text,
    allowed_statuses text,
    tags text
);
-- allowed_statuses / tags are TEXT holding IIQ's compact JSON array text, exactly as Part 1 stores them.

-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_task_result (IIQ TaskResult).
--
-- Mirrors Part 1's iiq_native.kf_task_result exactly: taskresultid uuid PK, the 26 TaskResult fields (3 integer,
-- 3 boolean, messages/attributes jsonb, 6 timestamps), record_hash, lineage, is_deleted / deleted_at. The application
-- ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_task_result.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_task_result (
    taskresultid uuid PRIMARY KEY,
    source_id text,
    name text,
    type text,
    completion_status text,
    definition_name text,
    launcher text,
    host text,
    target_name text,
    target_class text,
    target_id text,
    schedule text,
    progress text,
    percent_complete integer,
    run_length integer,
    pending_signoffs integer,
    partitioned boolean,
    terminate_requested boolean,
    complete boolean,
    launched_at timestamp with time zone,
    completed_at timestamp with time zone,
    expiration_at timestamp with time zone,
    verified_at timestamp with time zone,
    messages jsonb,
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

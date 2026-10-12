-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_task_schedule (IIQ TaskSchedule).
--
-- Mirrors Part 1's iiq_native.kf_task_schedule exactly: taskscheduleid uuid PK, the 18 TaskSchedule fields
-- (delete_on_finish boolean, cron_expressions/arguments jsonb, 6 timestamps), record_hash, lineage, is_deleted /
-- deleted_at. The application ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_task_schedule.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_task_schedule (
    taskscheduleid uuid PRIMARY KEY,
    source_id text,
    name text,
    description text,
    definition_name text,
    state text,
    new_state text,
    launcher text,
    host text,
    last_launch_error text,
    delete_on_finish boolean,
    last_execution_at timestamp with time zone,
    next_execution_at timestamp with time zone,
    next_actual_execution_at timestamp with time zone,
    resume_at timestamp with time zone,
    cron_expressions jsonb,
    arguments jsonb,
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

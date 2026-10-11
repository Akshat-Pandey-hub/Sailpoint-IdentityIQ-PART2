-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_workflow_definition (IIQ Workflow definitions).
--
-- Mirrors Part 1's iiq_native.kf_workflow_definition exactly (columns, types, order, PK). The application ALSO
-- creates it automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_workflow_definition.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_workflow_definition (
    workflowid uuid PRIMARY KEY,
    source_id text,
    name text,
    type text,
    handler text,
    description text,
    task_type text,
    definition jsonb,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    src_object_id text,
    src_natural_key text,
    extraction_run_id text,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);
-- workflowid = canonical IIQ Workflow id (fallback "native-workflow|"+id); src_object_id = source_id;
-- src_natural_key = workflow name.

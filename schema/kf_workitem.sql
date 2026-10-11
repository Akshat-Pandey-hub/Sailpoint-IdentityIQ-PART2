-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_workitem (live / open IIQ WorkItems).
--
-- Mirrors Part 1's iiq_native.kf_workitem exactly (columns, types, order, PK). The application ALSO creates it
-- automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_workitem.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_workitem (
    workitemid uuid PRIMARY KEY,
    source_id text,
    name text,
    type text,
    state text,
    level text,
    requester_id text,
    requester_name text,
    assignee_id text,
    assignee_name text,
    owner_id text,
    owner_name text,
    completer text,
    completion_comments text,
    handler text,
    notification_name text,
    identity_request_id text,
    target_id text,
    target_name text,
    certification_id text,
    certification_entity_id text,
    certification_item_id text,
    entity_type text,
    certification_related boolean,
    workflow_case_id text,
    workflow_case_name text,
    expiration timestamp with time zone,
    expiration_date timestamp with time zone,
    notification timestamp with time zone,
    wake_up_date timestamp with time zone,
    escalation_count integer,
    reminders integer,
    reminders_sent integer,
    expired boolean,
    expirable boolean,
    approval_set_item_count integer,
    comments jsonb,
    sign_offs jsonb,
    owner_history jsonb,
    approval_set_items jsonb,
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
-- workitemid = canonical IIQ WorkItem id (no fallback). Approvals / provisioning txns reference work items by RAW id;
-- completed work items live in WorkItemArchive (the separate workitem-archives entity).

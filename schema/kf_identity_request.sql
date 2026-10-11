-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_identity_request (IIQ IdentityRequest).
--
-- Mirrors Part 1's iiq_native.kf_identity_request exactly (columns, types, order, PK). The application ALSO
-- creates the schema + table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is
-- optional — provided for a manual, reviewable, repeatable init.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity_request.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_identity_request (
    identityrequestid uuid PRIMARY KEY,
    source_id text,
    name text,
    type text,
    user_friendly_type text,
    state text,
    source text,
    source_object text,
    completion_status text,
    execution_status text,
    priority text,
    requester_id text,
    requester_display_name text,
    target_id text,
    target_display_name text,
    external_ticket_id text,
    process_id text,
    task_result_id text,
    executing boolean,
    failure boolean,
    rejected boolean,
    successful boolean,
    terminated boolean,
    incomplete boolean,
    iiq_only boolean,
    provisioning_complete boolean,
    end_date timestamp with time zone,
    verified timestamp with time zone,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    owner_id text,
    owner_name text,
    errors jsonb,
    item_count integer,
    approval_count integer,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);
-- identityrequestid = canonical IIQ IdentityRequest id (no fallback: a request without one is rejected, as in Part 1).

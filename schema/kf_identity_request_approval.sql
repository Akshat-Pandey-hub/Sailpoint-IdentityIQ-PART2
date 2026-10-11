-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_identity_request_approval (IdentityRequest approval summaries).
--
-- Mirrors Part 1's iiq_native.kf_identity_request_approval exactly (columns, types, order, PK). The application
-- ALSO creates the schema + table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is
-- optional — provided for a manual, reviewable, repeatable init.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity_request_approval.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_identity_request_approval (
    identityrequestapprovalid uuid PRIMARY KEY,
    request_source_id text,
    request_name text,
    work_item_id text,
    work_item_type text,
    owner text,
    owner_id text,
    completer text,
    approved boolean,
    state text,
    state_key text,
    type_key text,
    start_date timestamp with time zone,
    end_date timestamp with time zone,
    approval_item_count integer,
    approval_index integer,
    comments jsonb,
    sign_off jsonb,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);
-- identityrequestapprovalid = name-based UUID over request_source_id|work_item_id|owner|approval_index
-- (an approval summary has no IIQ id of its own); parent request = request_source_id, work item = work_item_id.

-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_identity only (first milestone).
--
-- This mirrors Part 1's iiq_native.kf_identity exactly. The application ALSO creates the schema + table
-- automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is optional — provided for
-- a manual, reviewable, repeatable init.
--
-- Replace ':schema' below with your PG_DEST_SCHEMA (default iiq_native_part2), e.g. with psql:
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity.sql
-- (psql substitutes :schema). It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_identity (
    userid uuid PRIMARY KEY,
    source_id text,
    name text,
    display_name text,
    displayable_name text,
    first_name text,
    last_name text,
    email text,
    inactive boolean,
    type text,
    correlated boolean,
    manager_status boolean,
    manager_id text,
    manager_name text,
    administrator_id text,
    administrator_name text,
    accounts jsonb,
    assigned_roles jsonb,
    detected_roles jsonb,
    role_assignments jsonb,
    role_detections jsonb,
    role_requests jsonb,
    mitigation_expirations jsonb,
    capabilities jsonb,
    controlled_scopes jsonb,
    attributes jsonb,
    score text,
    full_name text,
    is_protected boolean,
    is_needs_refresh boolean,
    is_correlated_overridden boolean,
    is_workgroup boolean,
    auth_application text,
    auth_account text,
    pending_refresh_workflow text,
    password_expiration timestamp with time zone,
    auth_lock_start timestamp with time zone,
    use_by timestamp with time zone,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    last_refresh timestamp with time zone,
    last_login timestamp with time zone,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);
-- NOTE: kf_identity intentionally has NO record_hash column.

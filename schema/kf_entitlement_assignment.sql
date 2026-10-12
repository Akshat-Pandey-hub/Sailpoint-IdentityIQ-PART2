-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_entitlement_assignment (SQL report "Query 1" result table).
--
-- Mirrors Part 1's iiq_native.kf_entitlement_assignment exactly: the query's 29 SELECT aliases as text, an identity
-- row id, and source_system / source_interface (native_iiq_jdbc) / extraction_run_id / extracted_at. The application
-- ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_entitlement_assignment.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_entitlement_assignment (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identity_id text,
    user_id text,
    user_name text,
    application_name text,
    account_name text,
    entitlement_attribute text,
    entitlement_value text,
    assigned text,
    granted_by_role text,
    entitlement_source text,
    entitlement_assignment_id text,
    entitlement_assigner text,
    entitlement_created text,
    entitlement_modified text,
    role_id text,
    role_name text,
    role_display_name text,
    role_type text,
    role_requestable text,
    identity_request_id text,
    access_request_id text,
    request_type text,
    requested_by text,
    request_date text,
    request_status text,
    request_operation text,
    provisioning_state text,
    assignment_type text,
    assignment_reason text,
    source_system text,
    source_interface text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now()
);
-- Load is replace-all per run (DELETE + INSERT in one transaction); id is a row number, not a business key.

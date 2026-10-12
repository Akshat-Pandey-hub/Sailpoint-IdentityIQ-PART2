-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_entitlement_certification_status (SQL report "Query 4" result table).
--
-- Mirrors Part 1's iiq_native.kf_entitlement_certification_status exactly: the query's 14 SELECT aliases as text, an
-- identity row id, and source_system / source_interface (native_iiq_jdbc) / extraction_run_id / extracted_at. The
-- application ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_entitlement_certification_status.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_entitlement_certification_status (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id text,
    user_name text,
    manager_user_id text,
    manager_name text,
    application_name text,
    account_name text,
    entitlement_attribute text,
    entitlement_value text,
    entitlement_assigned_date text,
    last_certification_name text,
    last_certified_date text,
    certifier text,
    certification_decision text,
    never_certified text,
    source_system text,
    source_interface text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now()
);
-- Load is replace-all per run (DELETE + INSERT in one transaction); id is a row number, not a business key.

-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_entitlement_certification (SQL report "Query 3" result table).
--
-- Mirrors Part 1's iiq_native.kf_entitlement_certification exactly: the query's 9 SELECT aliases as text, an identity
-- row id, and source_system / source_interface (native_iiq_jdbc) / extraction_run_id / extracted_at. The application
-- ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_entitlement_certification.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_entitlement_certification (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id text,
    user_name text,
    application_name text,
    account_name text,
    entitlement_attribute text,
    entitlement_value text,
    entitlement_assigned_date text,
    entitlement_modified_date text,
    never_certified text,
    source_system text,
    source_interface text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now()
);
-- Load is replace-all per run (DELETE + INSERT in one transaction); id is a row number, not a business key.

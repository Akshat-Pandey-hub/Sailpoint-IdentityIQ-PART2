-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_workgroup_members (SQL report "Query 2" result table).
--
-- Mirrors Part 1's iiq_native.kf_workgroup_members exactly: the query's 7 SELECT aliases as text, an identity row id,
-- and source_system / source_interface (native_iiq_jdbc) / extraction_run_id / extracted_at. The application ALSO
-- creates it automatically on first run. Distinct from the native edge table kf_workgroup_member.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_workgroup_members.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_workgroup_members (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workgroup_name text,
    user_name text,
    display_name text,
    first_name text,
    last_name text,
    email text,
    user_status text,
    source_system text,
    source_interface text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now()
);
-- Load is replace-all per run (DELETE + INSERT in one transaction); id is a row number, not a business key.

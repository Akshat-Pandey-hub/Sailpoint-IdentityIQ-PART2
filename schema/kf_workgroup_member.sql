-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_workgroup_member (workgroup <-> member identity edges).
--
-- Mirrors Part 1's iiq_native.kf_workgroup_member exactly (columns, types, order, PK). The application ALSO creates
-- it automatically on first run. (Not to be confused with kf_workgroup_members, the SQL-report Query 2 table.)
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_workgroup_member.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_workgroup_member (
    id uuid PRIMARY KEY,
    workgroup_id uuid,
    identity_id uuid,
    member_name text,
    first_name text,
    last_name text,
    member_is_workgroup boolean,
    src_system text,
    src_interface text,
    src_object_type text,
    src_object_id text,
    src_natural_key text,
    extraction_run_id text,
    record_hash text,
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone,
    extracted_at timestamp with time zone NOT NULL DEFAULT now()
);
-- workgroup_id / identity_id hold CANONICAL UUIDs (kf_workgroup.workgroupid / kf_identity.userid form); the raw ids are
-- in src_natural_key (workgroup|identity) and src_object_id (identity).

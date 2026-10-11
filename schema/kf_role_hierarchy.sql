-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_role_hierarchy (role-to-role edges: INHERITANCE / REQUIREMENT / PERMIT).
--
-- Mirrors Part 1's iiq_native.kf_role_hierarchy exactly (columns, types, order, NOT NULLs, PK). The application
-- ALSO creates it automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_role_hierarchy.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_role_hierarchy (
    rolehierarchyid uuid PRIMARY KEY,
    source_role_id text NOT NULL,
    role_id uuid,
    role_name text,
    related_role_source_id text NOT NULL,
    related_role_id uuid,
    related_role_name text,
    relationship_type text NOT NULL,
    source_system text,
    source_interface text,
    source_object_type text,
    src_object_id text,
    src_natural_key text NOT NULL,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    record_hash text,
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);
-- source_role_id/role_name = the owning role; related_role_source_id/related_role_name = the related role.
-- rolehierarchyid = deterministicUuid("native-role-hierarchy|" + src_natural_key); role_id, related_role_id,
-- src_object_id and src_natural_key are derived by Part 2 exactly as Part 1 derives them (not in the response).

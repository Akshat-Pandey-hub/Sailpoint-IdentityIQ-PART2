-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_group_definition (IIQ GroupDefinition: Populations and Groups).
--
-- Mirrors Part 1's iiq_native.kf_group_definition exactly: groupid uuid PK, the 15 GroupDefinition fields (4 boolean,
-- 3 timestamps), record_hash, lineage, is_deleted / deleted_at. The application ALSO creates it automatically.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_group_definition.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_group_definition (
    groupid uuid PRIMARY KEY,
    source_id text,
    name text,
    type text,
    factory_id text,
    factory_name text,
    filter_expression text,
    is_private boolean,
    indexed boolean,
    null_group boolean,
    name_unique boolean,
    owner_id text,
    owner_name text,
    last_refresh timestamp with time zone,
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

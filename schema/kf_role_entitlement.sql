-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_role_entitlement (what each role grants: Bundle profile
-- constraints and permissions).
--
-- Mirrors Part 1's iiq_native.kf_role_entitlement exactly (columns, types, order, NOT NULLs, PK). The application
-- ALSO creates it automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_role_entitlement.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- import-role-entitlement also needs :schema.kf_entitlement (run import-entitlement first).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_role_entitlement (
    roleentitlementid uuid PRIMARY KEY,
    source_role_id text NOT NULL,
    role_id uuid,
    role_name text,
    entitlement_id uuid,
    entitlement_type text NOT NULL,
    application_id text,
    application_name text,
    profile_ordinal integer,
    constraint_path text,
    filter_expression text,
    filter_value jsonb,
    filter_operation text,
    attribute_name text,
    attribute_value text,
    permission_target text,
    permission_rights text,
    permission_rights_list jsonb,
    permission_annotation text,
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
-- roleentitlementid = deterministicUuid("native-role-entitlement|" + src_natural_key); role_id, src_object_id,
-- src_natural_key and entitlement_id are derived by Part 2 exactly as Part 1 derives them (not in the response).

-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_account_entitlement (entitlement values / permissions on accounts).
--
-- Mirrors Part 1's iiq_native.kf_account_entitlement exactly (columns, types, order, PK), including the 7
-- columns Part 1 appended after deleted_at (type, permission_*). The application ALSO creates the schema +
-- table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_account_entitlement.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_account_entitlement (
    accountentitlementid uuid PRIMARY KEY,
    link_id text,
    identity_id text,
    identity_name text,
    application_id text,
    application_name text,
    native_identity text,
    instance text,
    attribute_name text,
    attribute_value text,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone,
    type text,
    permission_target text,
    permission_rights text,
    permission_rights_list jsonb,
    permission_annotation text,
    permission_aggregation_source text,
    permission_attributes jsonb
);
-- accountentitlementid = name-based UUID: ATTRIBUTE edges over link_id|attribute_name|attribute_value,
-- PERMISSION / TARGET_PERMISSION edges over link_id|type|permission_target|permission_rights|permission_annotation.

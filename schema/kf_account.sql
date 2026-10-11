-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_account.
--
-- Mirrors Part 1's iiq_native.kf_account exactly (columns, types, order, PK). The application ALSO creates
-- the schema + table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is
-- optional — provided for a manual, reviewable, repeatable init.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_account.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_account (
    accountid uuid PRIMARY KEY,
    source_id text,
    link_uuid text,
    native_identity text,
    display_name text,
    displayable_name text,
    instance text,
    component_ids text,
    application_id text,
    application_name text,
    identity_id text,
    identity_name text,
    disabled boolean,
    locked boolean,
    composite boolean,
    manually_correlated boolean,
    has_entitlements boolean,
    iiq_disabled boolean,
    iiq_locked boolean,
    permissions jsonb,
    target_permissions jsonb,
    attributes jsonb,
    entitlement_attributes jsonb,
    attribute_metadata jsonb,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    last_refresh timestamp with time zone,
    last_target_aggregation timestamp with time zone,
    significant_modified timestamp with time zone,
    prior_significant_modified timestamp with time zone,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);

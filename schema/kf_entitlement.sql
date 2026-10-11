-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_entitlement.
--
-- Mirrors Part 1's iiq_native.kf_entitlement exactly (columns, types, order, PK). The application ALSO
-- creates the schema + table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file
-- is optional — provided for a manual, reviewable, repeatable init.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_entitlement.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_entitlement (
    entitlementid uuid PRIMARY KEY,
    source_id text,
    name text,
    value text,
    display_name text,
    displayable_name text,
    attribute text,
    type text,
    ma_uuid text,
    reference_attribute text,
    purview text,
    application_id text,
    application_name text,
    instance text,
    native_identity text,
    requestable boolean,
    is_group boolean,
    is_permission boolean,
    uncorrelated boolean,
    aggregated boolean,
    iiq_elevated_access boolean,
    owner_id text,
    owner_name text,
    description text,
    descriptions jsonb,
    permissions jsonb,
    target_permissions jsonb,
    inheritance jsonb,
    associations jsonb,
    attributes jsonb,
    classifications jsonb,
    classification_names jsonb,
    classification_display_names jsonb,
    member_attribute text,
    full_name text,
    is_group_type boolean,
    is_inactive boolean,
    is_auto_promotion boolean,
    is_differencable boolean,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    last_refresh timestamp with time zone,
    last_target_aggregation timestamp with time zone,
    source_hash text,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);
-- source_hash = IIQ's own hash (from the response); record_hash = Part 2/Part 1 SHA-256 change-detection hash.

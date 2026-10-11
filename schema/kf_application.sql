-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_application.
--
-- Mirrors Part 1's iiq_native.kf_application exactly (columns, types, order, PK). The application ALSO
-- creates the schema + table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file
-- is optional — provided for a manual, reviewable, repeatable init.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_application.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_application (
    applicationid uuid PRIMARY KEY,
    source_id text,
    name text,
    description text,
    type text,
    connector text,
    features_string text,
    profile_class text,
    proxied_name text,
    cluster text,
    icon text,
    aggregation_types text,
    before_provisioning_rule text,
    after_provisioning_rule text,
    account_schema_correlation_rule text,
    account_schema_customization_rule text,
    account_schema_creation_rule text,
    account_schema_refresh_rule text,
    application_creation_rule text,
    account_schema_correlation_rule_id text,
    account_schema_customization_rule_id text,
    account_schema_creation_rule_id text,
    account_schema_refresh_rule_id text,
    application_creation_rule_id text,
    score integer,
    authoritative boolean,
    case_insensitive boolean,
    logical boolean,
    composite boolean,
    authentication_resource boolean,
    activity_enabled boolean,
    in_maintenance boolean,
    manages_other_apps boolean,
    native_change_detection_enabled boolean,
    supports_provisioning boolean,
    supports_account_only boolean,
    supports_additional_accounts boolean,
    supports_authenticate boolean,
    supports_group_provisioning boolean,
    supports_direct_permissions boolean,
    sync_provisioning boolean,
    owner_id text,
    owner_name text,
    secondary_owners jsonb,
    remediators jsonb,
    dependencies jsonb,
    schemas jsonb,
    descriptions jsonb,
    attributes jsonb,
    provisioning_config text,
    account_correlation_config text,
    manager_correlation_rule text,
    manager_correlation_filter text,
    service_account_filter jsonb,
    rpa_account_filter jsonb,
    disable_account_filter jsonb,
    lock_account_filter jsonb,
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

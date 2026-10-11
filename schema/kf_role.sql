-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_role.
--
-- Mirrors Part 1's iiq_native.kf_role exactly (columns, types, order, PK), including the 6 columns Part 1
-- appended after deleted_at (role_type_definition … selector). The application ALSO creates the schema +
-- table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_role.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_role (
    roleid uuid PRIMARY KEY,
    source_id text,
    name text,
    display_name text,
    displayable_name text,
    full_name text,
    description text,
    type text,
    assignment_id text,
    activity_enabled boolean,
    allow_duplicate_accounts boolean,
    allow_multiple_assignments boolean,
    auto_promotion boolean,
    differencable boolean,
    iiq_elevated_access boolean,
    merge_templates boolean,
    or_profiles boolean,
    pending_delete boolean,
    has_selector boolean,
    risk_score_weight integer,
    owner_id text,
    owner_name text,
    activation_date timestamp with time zone,
    deactivation_date timestamp with time zone,
    descriptions jsonb,
    attributes jsonb,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone,
    role_type_definition text,
    applications text,
    monitored_applications text,
    scorecard text,
    selector_summary text,
    selector jsonb
);

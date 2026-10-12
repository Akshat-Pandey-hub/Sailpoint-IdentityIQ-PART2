-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_violation (IIQ PolicyViolation).
--
-- Mirrors Part 1's iiq_native.kf_violation exactly (columns, types, order, PK). The application ALSO creates it
-- automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_violation.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_violation (
    violationid uuid PRIMARY KEY,
    source_id text,
    name text,
    identity_id text,
    identity_name text,
    policy_id text,
    policy_name text,
    constraint_id text,
    constraint_name text,
    status text,
    active boolean,
    left_bundles text,
    right_bundles text,
    entitlements_marked_for_remediation text,
    bundles_marked_for_remediation text,
    relevant_apps jsonb,
    violating_entitlements jsonb,
    arguments jsonb,
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
-- Links (raw IIQ ids): policy_id -> kf_policy.source_id, constraint_id -> kf_policy_constraint.source_id.

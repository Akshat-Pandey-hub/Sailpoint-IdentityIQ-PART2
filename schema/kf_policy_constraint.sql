-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_policy_constraint (IIQ BaseConstraint rules of a Policy).
--
-- Mirrors Part 1's iiq_native.kf_policy_constraint exactly (columns, types, order, PK). The application ALSO
-- creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_policy_constraint.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_policy_constraint (
    policyconstraintid uuid PRIMARY KEY,
    source_id text,
    policy_id text,
    policy_name text,
    name text,
    description text,
    constraint_type text,
    weight integer,
    compensating_control text,
    violation_owner_id text,
    violation_owner_name text,
    violation_owner_type text,
    left_bundles jsonb,
    right_bundles jsonb,
    selectors jsonb,
    selector_count integer,
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
-- Parent: policy_id = the Policy's RAW IIQ id (kf_policy.source_id).

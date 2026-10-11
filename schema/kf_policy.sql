-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_policy (IIQ Policy).
--
-- Mirrors Part 1's iiq_native.kf_policy exactly (columns, types, order, PK), including the 9 columns Part 1
-- appended after deleted_at (state … is_template). The application ALSO creates it automatically on first run
-- (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_policy.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_policy (
    policyid uuid PRIMARY KEY,
    source_id text,
    name text,
    type text,
    type_key text,
    description text,
    descriptions jsonb,
    executor text,
    violation_owner_id text,
    violation_owner_name text,
    constraint_count integer,
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
    state text,
    violation_rule text,
    violation_workflow text,
    signature text,
    certification_actions text,
    violation_owner_type text,
    violation_owner_rule_id text,
    violation_owner_rule_name text,
    is_template boolean
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_certification_item (IIQ CertificationItem: each reviewed line + decision).
--
-- Mirrors Part 1's iiq_native.kf_certification_item exactly (columns, types, order, PK), including the 2 columns
-- Part 1 appended after deleted_at. The application ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_certification_item.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_certification_item (
    certificationitemid uuid PRIMARY KEY,
    source_id text,
    certification_id text,
    entity_id text,
    identity text,
    type text,
    sub_type text,
    bundle text,
    bundle_assignment_id text,
    exception_application text,
    exception_attribute_name text,
    exception_attribute_value text,
    exception_permission_target text,
    exception_permission_right text,
    account_group text,
    phase text,
    summary_status text,
    completed timestamp with time zone,
    last_decision timestamp with time zone,
    expiration_date timestamp with time zone,
    finished_date timestamp with time zone,
    iiq_elevated_access boolean,
    reviewed boolean,
    delegated boolean,
    acted_upon boolean,
    historical boolean,
    expired boolean,
    target_id text,
    target_name text,
    short_description text,
    violation_summary text,
    application_names jsonb,
    classification_names jsonb,
    action_status text,
    action_decision_date timestamp with time zone,
    action_decision_certification_id text,
    action_remediation_action text,
    action_actor_name text,
    action_actor_display_name text,
    action_comments text,
    action_completion_comments text,
    action_owner_name text,
    action_mitigation_expiration timestamp with time zone,
    action_is_approved boolean,
    action_is_remediation boolean,
    action_is_mitigation boolean,
    action_is_delegation boolean,
    action_is_revoke_account boolean,
    action_is_auto_decision boolean,
    action_is_bulk_certified boolean,
    owner_id text,
    owner_name text,
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
    policy_violation_id text,
    role_assignment text
);
-- Links (raw IIQ ids): certification_id -> kf_certification.source_id, entity_id -> kf_certification_entity.source_id.

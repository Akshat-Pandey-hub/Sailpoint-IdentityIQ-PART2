-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_certification_entity (IIQ CertificationEntity: who/what is reviewed).
--
-- Mirrors Part 1's iiq_native.kf_certification_entity exactly (columns, types, order, PK). The application ALSO
-- creates it automatically on first run, so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_certification_entity.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_certification_entity (
    certificationentityid uuid PRIMARY KEY,
    source_id text,
    certification_id text,
    identity text,
    application text,
    native_identity text,
    account_group text,
    first_name text,
    last_name text,
    full_name text,
    reference_attribute text,
    schema_object_type text,
    snapshot_id text,
    pending_certification text,
    type text,
    summary_status text,
    entity_delegated boolean,
    entity_delegation_status text,
    composite_score integer,
    target_id text,
    target_name text,
    target_display_name text,
    completed timestamp with time zone,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    owner_id text,
    owner_name text,
    action_status text,
    action_decision_date timestamp with time zone,
    action_remediation_action text,
    action_actor_name text,
    action_actor_display_name text,
    action_comments text,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);
-- Parent: certification_id = the parent Certification's RAW IIQ id (kf_certification.source_id).

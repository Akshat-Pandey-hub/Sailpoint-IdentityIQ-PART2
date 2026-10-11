-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_identity_entitlement (identity↔entitlement, IIQ IdentityEntitlement).
--
-- Mirrors Part 1's iiq_native.kf_identity_entitlement exactly (columns, types, order, PK). The application
-- ALSO creates the schema + table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this
-- file is optional — provided for a manual, reviewable, repeatable init.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity_entitlement.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_identity_entitlement (
    entitlementid uuid PRIMARY KEY,
    source_id text,
    identity_id text,
    identity_name text,
    application_id text,
    application_name text,
    native_identity text,
    instance text,
    attribute_name text,
    attribute_value text,
    value_list jsonb,
    type text,
    display_name text,
    annotation text,
    assigned boolean,
    granted_by_role boolean,
    allowed boolean,
    connected boolean,
    aggregation_state text,
    source text,
    source_object text,
    assigner text,
    assignment_id text,
    assignment_note text,
    source_assignable_roles text,
    source_detected_roles text,
    certification_item_id text,
    pending_certification_item_id text,
    request_item_id text,
    pending_request_item_id text,
    start_date timestamp with time zone,
    end_date timestamp with time zone,
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
-- entitlementid here identifies the IdentityEntitlement row (canonical source_id, else the composite natural key),
-- not the catalog entitlement in kf_entitlement.

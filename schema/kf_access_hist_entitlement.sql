-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_access_hist_entitlement (IIQ Access-History entitlement captures,
-- APPEND-ONLY).
--
-- Mirrors Part 1's iiq_native.kf_access_hist_entitlement exactly, including column order: hist_capture_id uuid PK,
-- the capture fields (7 boolean, 4 timestamps, capture_json text, 3 jsonb), record_hash, lineage, and the 4 columns
-- Part 1 added after extracted_at. No soft-delete columns. The application ALSO creates it automatically.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_access_hist_entitlement.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_access_hist_entitlement (
    hist_capture_id uuid PRIMARY KEY,
    source_id text,
    entity_id text,
    entity_name text,
    identity_id text,
    identity_name text,
    identity_entitlement_id text,
    application_id text,
    application_name text,
    native_identity text,
    instance text,
    display_value text,
    attribute_name text,
    attribute_value text,
    type text,
    granted_by_role boolean,
    role_id text,
    request_item_id text,
    pending_request_item_id text,
    certification_item_id text,
    deleted boolean,
    effective_date timestamp with time zone,
    extended_to_date timestamp with time zone,
    latest boolean,
    compressed boolean,
    brief boolean,
    full_capture boolean,
    patch boolean,
    smart_hash text,
    full_hash text,
    json_format text,
    transform_type text,
    patch_doc_parent text,
    capture_json text,
    attributes jsonb,
    extended_attributes jsonb,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    name text,
    compressed_property_flag text,
    compressed_property_flag_value text,
    compressible_property_names jsonb
);
-- Load is INSERT … ON CONFLICT (hist_capture_id) DO NOTHING: captures are never updated or deleted.

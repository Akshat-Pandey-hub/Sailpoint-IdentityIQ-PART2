-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_identity_request_item (IIQ IdentityRequestItem).
--
-- Mirrors Part 1's iiq_native.kf_identity_request_item exactly (columns, types, order, PK). The application ALSO
-- creates the schema + table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is
-- optional — provided for a manual, reviewable, repeatable init.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity_request_item.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_identity_request_item (
    identityrequestitemid uuid PRIMARY KEY,
    source_id text,
    request_source_id text,
    request_name text,
    application text,
    attribute_name text,
    attribute_value text,
    operation text,
    managed_attribute_type text,
    assignment_id text,
    native_identity text,
    instance text,
    approver_name text,
    approval_state text,
    approved boolean,
    approval_complete boolean,
    rejected boolean,
    provisioning_state text,
    provisioning_engine text,
    provisioning_request_id text,
    provisioning_complete boolean,
    provisioning_failed boolean,
    compilation_status text,
    owner_name text,
    requester_comments text,
    expansion boolean,
    expansion_cause text,
    expansion_info text,
    retries integer,
    iiq boolean,
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
-- identityrequestitemid = the item's own IIQ id (canonical), not the parent request; parent = request_source_id / request_name.

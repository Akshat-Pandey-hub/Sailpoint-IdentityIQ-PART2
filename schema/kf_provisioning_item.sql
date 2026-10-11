-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_provisioning_item (Attribute/PermissionRequests of a provisioning txn).
--
-- Mirrors Part 1's iiq_native.kf_provisioning_item exactly (columns, types, order, PK). The application ALSO
-- creates it automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_provisioning_item.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_provisioning_item (
    provisioningitemid uuid PRIMARY KEY,
    txn_source_id text,
    identity_name text,
    item_type text,
    operation text,
    application_name text,
    native_identity text,
    instance text,
    account_operation text,
    name text,
    value text,
    value_json jsonb,
    assignment_id text,
    assignment boolean,
    permission_target text,
    permission_rights text,
    request_id text,
    item_index integer,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    src_object_id text,
    src_natural_key text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);
-- provisioningitemid = name-based UUID over txn_source_id|item_type|operation|name|value_json|value|item_index (not the
-- txn id); parent txn = txn_source_id = src_object_id; src_natural_key = txn_source_id|item_type|item_index.

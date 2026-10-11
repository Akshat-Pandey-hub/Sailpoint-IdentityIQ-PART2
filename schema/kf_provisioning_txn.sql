-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_provisioning_txn (IIQ ProvisioningTransaction).
--
-- Mirrors Part 1's iiq_native.kf_provisioning_txn exactly (columns, types, order, PK). The application ALSO
-- creates it automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file is optional.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_provisioning_txn.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_provisioning_txn (
    provisioningtxnid uuid PRIMARY KEY,
    source_id text,
    name text,
    operation text,
    type text,
    status text,
    source text,
    integration text,
    forced boolean,
    identity_name text,
    identity_display_name text,
    application_name text,
    native_identity text,
    account_display_name text,
    certification_id text,
    certification_name text,
    access_request_id text,
    wait_work_item_id text,
    manual_work_item_id text,
    ticket_id text,
    retry_count integer,
    timed_out boolean,
    filtered boolean,
    retry_request_id text,
    last_retry timestamp with time zone,
    plan_result_status text,
    plan_result_request_id text,
    plan_result_errors jsonb,
    account_request_operation text,
    request_id text,
    item_count integer,
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
    deleted_at timestamp with time zone
);
-- Items link by RAW id: kf_provisioning_item.txn_source_id = kf_provisioning_txn.source_id (not provisioningtxnid).

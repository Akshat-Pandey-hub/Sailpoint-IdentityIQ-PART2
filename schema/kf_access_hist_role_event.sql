-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_access_hist_role_event (IIQ Access-History role events,
-- APPEND-ONLY).
--
-- Mirrors Part 1's iiq_native.kf_access_hist_role_event exactly: hist_event_id uuid PK, the 38 event fields (all
-- text except event_date / created_at / modified_at; event_detail_json is TEXT), record_hash, lineage. No soft-delete
-- columns. The application ALSO creates it automatically on first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_access_hist_role_event.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_access_hist_role_event (
    hist_event_id uuid PRIMARY KEY,
    source_id text,
    name text,
    entity_id text,
    entity_name text,
    defined_entity_name text,
    event_type text,
    event_category text,
    event_source_type text,
    event_date timestamp with time zone,
    event_detail_json text,
    prev_capture_id text,
    capture_id text,
    audit_event_id text,
    property_name text,
    old_value text,
    new_value text,
    account_id text,
    acct_app_id text,
    acct_app_name text,
    acct_app_instance text,
    acct_display_name text,
    acct_native_id text,
    pending_request_item_id text,
    request_item_id text,
    identity_request_id text,
    identity_entitlement_id text,
    qry_property1 text,
    qry_property2 text,
    qry_property3 text,
    qry_property4 text,
    qry_property5 text,
    qry_property6 text,
    qry_property7 text,
    qry_property8 text,
    qry_property9 text,
    qry_property10 text,
    created_at timestamp with time zone,
    modified_at timestamp with time zone,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now()
);
-- Load is INSERT … ON CONFLICT (hist_event_id) DO NOTHING: events are never updated or deleted.

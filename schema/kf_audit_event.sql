-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_audit_event (IIQ AuditEvent, APPEND-ONLY).
--
-- Mirrors Part 1's iiq_native.kf_audit_event exactly: auditid uuid PK, the 19 AuditEvent fields (attributes jsonb,
-- created_at timestamptz), record_hash, and the source_system / source_interface / source_object_type /
-- extraction_run_id / extracted_at lineage. No soft-delete columns. The application ALSO creates it automatically.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_audit_event.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_audit_event (
    auditid uuid PRIMARY KEY,
    source_id text,
    action text,
    audit_source text,
    target text,
    application text,
    account_name text,
    instance text,
    attribute_name text,
    attribute_value text,
    interface_name text,
    server_host text,
    client_host text,
    tracking_id text,
    string1 text,
    string2 text,
    string3 text,
    string4 text,
    attributes jsonb,
    created_at timestamp with time zone,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now()
);
-- Load is INSERT … ON CONFLICT (auditid) DO NOTHING: events are never updated or deleted.

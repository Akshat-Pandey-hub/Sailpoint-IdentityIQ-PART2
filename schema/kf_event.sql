-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_event (append-only derived EVENT zone).
--
-- Same table as Part 1's iiq_native.kf_event (column order included). Owned by Part 2: rows are DERIVED by
-- derive-native-events from native tables already loaded into this schema (kf_identity_request_approval,
-- kf_task_result, kf_provisioning_txn, kf_workitem_archive, kf_certification_item) — never fetched from the KF Agent.
-- event_id = UUID.nameUUIDFromBytes(src_system|src_object_type|src_object_id|event_fingerprint); rows are inserted
-- with ON CONFLICT (event_id) DO NOTHING and are never updated or deleted. extraction_run_id = kf_extraction_run id
-- of the derivation run that first recorded the event. The application ALSO creates it on the first run.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_event.sql
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_event (
    event_id uuid PRIMARY KEY,
    src_system text,
    src_object_type text,
    src_object_id text,
    event_type text,
    event_fingerprint text,
    src_event_ts timestamptz,
    src_event_ts_precision text,
    extraction_run_id text,
    src_interface text,
    raw_ref text,
    event_detail jsonb,
    extracted_at timestamptz NOT NULL DEFAULT now()
);

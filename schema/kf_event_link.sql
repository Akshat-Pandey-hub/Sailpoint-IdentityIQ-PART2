-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_event_link (append-only derived native event links).
--
-- Same table as Part 1's NATIVE iiq_native.kf_event_link (NativeEventLinkRepository; column order and primary key as
-- in the reference dump; no foreign key). NOT Part 1's legacy REST-pipeline kf_event_link (id / audit_event_id),
-- which is never created here. Owned by Part 2: rows are DERIVED by derive-native-event-links from native tables
-- already loaded into this schema, for events already stored in kf_event — never fetched from the KF Agent.
-- link_id = UUID.nameUUIDFromBytes(event_id|link_type|target_object_type|target_object_id); link_status is always
-- EXPLICIT; rows are inserted with ON CONFLICT (link_id) DO NOTHING and are never updated or deleted.
-- extraction_run_id = kf_extraction_run id of the run that first recorded the link. The application ALSO creates it
-- on the first run (and refuses to write into an existing kf_event_link that is not this shape).
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_event_link.sql
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_event_link (
    link_id uuid PRIMARY KEY,
    event_id uuid,
    src_object_type text,
    src_object_id text,
    target_object_type text,
    target_object_id text,
    link_type text,
    link_status text,
    extraction_run_id text,
    extracted_at timestamptz NOT NULL DEFAULT now()
);

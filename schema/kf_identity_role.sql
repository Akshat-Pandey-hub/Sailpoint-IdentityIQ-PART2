-- ─────────────────────────────────────────────────────────────────────────────
-- Destination schema init for Part 2 — kf_identity_role (identity↔role edges).
--
-- Mirrors Part 1's iiq_native.kf_identity_role exactly (columns, types, order, PK). The application ALSO
-- creates the schema + table automatically on first run (CREATE SCHEMA/TABLE IF NOT EXISTS), so this file
-- is optional — provided for a manual, reviewable, repeatable init.
--
--     psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity_role.sql
-- It writes to a SEPARATE schema; it never alters an existing populated schema.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS :schema;

CREATE TABLE IF NOT EXISTS :schema.kf_identity_role (
    identityroleid uuid PRIMARY KEY,
    identity_id text,
    identity_name text,
    role_id text,
    role_name text,
    relationship_type text,
    assignment_id text,
    assigner text,
    assigned_date timestamp with time zone,
    start_date timestamp with time zone,
    end_date timestamp with time zone,
    source text,
    negative boolean,
    manual boolean,
    detection_assignment_ids text,
    comments text,
    future_assignment boolean,
    promoted_soft_permit boolean,
    detection_date timestamp with time zone,
    targets jsonb,
    record_hash text,
    source_system text,
    source_interface text,
    source_object_type text,
    extraction_run_id text,
    extracted_at timestamp with time zone NOT NULL DEFAULT now(),
    is_deleted boolean NOT NULL DEFAULT false,
    deleted_at timestamp with time zone
);
-- identityroleid = name-based UUID over identity_id|role_id|relationship_type|assignment_id (edge has no IIQ id).

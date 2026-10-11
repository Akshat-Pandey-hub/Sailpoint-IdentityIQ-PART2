# KF Agent → PostgreSQL Ingestion (Part 2)

Consumes the existing **Part 1 KF Agent REST** JSON endpoints over HTTP and upserts the data into a
**configurable, separate** PostgreSQL schema that reuses the `iiq_native` table model. It does **not**
extract from SailPoint IdentityIQ and does **not** depend on `identityiq.jar`. It is an independent project —
Part 1 is untouched.

Pipeline: `Existing KF Agent HTTP endpoints → Part 2 → PostgreSQL`

First milestone implemented: **Identity** (`GET /kfagent/identities` → `<schema>.kf_identity`).

## How identity rows are keyed (the "two ids" explained)
`userid` (the uuid primary key) is **not** a new/random id — it is `source_id` rewritten in canonical UUID
form (`canonicalUuid(source_id)`), so the same identity always gets the same `userid`. `source_id` is kept
verbatim in its own column. Upsert is `INSERT … ON CONFLICT (userid) DO UPDATE`, so re-running updates in
place and never duplicates. The 7 source-metadata columns are generated at write time (see below);
`kf_identity` has **no** `record_hash`.

| Column | Value |
|---|---|
| `userid` (uuid PK) | `canonicalUuid(source_id)`; fallback `deterministicUuid("native-identity\|"+source_id or name)` |
| business columns | 1:1 from the response (same names), incl. `jsonb`, booleans, timestamps, nulls |
| `source_system` | `IdentityIQ` |
| `source_interface` | `native_iiq_java_api` |
| `source_object_type` | `sailpoint.object.Identity` |
| `extraction_run_id` | a fresh UUID per Part 2 run |
| `extracted_at` | `now()` |
| `is_deleted` / `deleted_at` | `false` / `null` (no soft-delete sweep in this milestone) |

## Build (Java 17, Maven)
```bash
mvn clean package
# -> target/kfagent-ingest.jar  (self-contained executable jar)
```
(Offline, matching the Part 1 toolchain: `mvn -o -Dmaven.repo.local=D:/m2/repository clean package`.)

## Configure (all via environment variables; nothing hardcoded)
| Variable | Required | Default | Purpose |
|---|---|---|---|
| `KFAGENT_BASE_URL` | yes | — | e.g. `http://127.0.0.1:8100` |
| `PG_HOST` | yes | — | destination PostgreSQL host |
| `PG_PORT` | no | `5432` | |
| `PG_DATABASE` | yes | — | destination database |
| `PG_DEST_SCHEMA` | no | `iiq_native_part2` | **separate** destination schema |
| `PG_USERNAME` | yes | — | |
| `PG_PASSWORD` | yes | — | |
| `KFAGENT_PAGE_SIZE` | no | `500` | page size for `?start=&limit=` |
| `HTTP_TIMEOUT_SECONDS` | no | `60` | |
| `KFAGENT_USERNAME` / `KFAGENT_PASSWORD` | no | — | Basic Auth, only if the agent is behind an auth proxy |

## Initialize the destination schema
The app auto-creates the schema + `kf_identity` on first run (`CREATE … IF NOT EXISTS`). To init manually
instead (reviewable, repeatable), use `schema/kf_identity.sql`:
```bash
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity.sql
```
It writes to a **separate** schema and never alters an existing populated schema.

## Run
```bash
export KFAGENT_BASE_URL=http://127.0.0.1:8100
export PG_HOST=<host> PG_DATABASE=<db> PG_USERNAME=<user> PG_PASSWORD=<pw>
export PG_DEST_SCHEMA=iiq_native_part2
java -jar target/kfagent-ingest.jar import-identity
```
Prints a credential-free config line, the run id, and an import summary (pages / fetched / inserted /
updated / failed, with up to 10 sample failures). Exit codes: `0` ok, `2` usage, `3` config, `4` HTTP/source,
`5` PostgreSQL, `6` completed with row failures, `1` unexpected.

## Verify against a separate test schema
1. Start Part 1: `java -jar iiq-migration-tool.jar start-kfagent` (serves `/kfagent/identities` on `:8100`).
2. Point `PG_DEST_SCHEMA` at a throwaway schema (e.g. `iiq_native_part2_test`) and run `import-identity`.
3. Check: `SELECT count(*) FROM iiq_native_part2_test.kf_identity;` equals the response row count; spot-check
   `userid = <source_id reformatted>`, business fields, `jsonb` round-trip, and the metadata columns.
4. **Run the import again** → row count unchanged, `userid`s identical, rows updated in place (idempotent).

## Tests
```bash
mvn test
```
- `CanonicalIdsTest` — `userid`/UUID generation + deterministic fallback (pins Part 1 parity).
- `IdentitySqlTest` — generated DDL + `ON CONFLICT (userid)` upsert shape, column/placeholder counts, no `record_hash`.
- `IdentityBindingTest` — response→column mapping via a recording JDBC stub: canonical `userid`, text/boolean,
  nested JSON → `jsonb`, ISO timestamps, SQL NULL for null/absent fields, metadata params. No live DB needed.

## Status / next
Identity is the complete first vertical slice (HTTP → parse → map → metadata → upsert → verification). The
column-spec + upsert engine is built to extend to the other ~39 endpoints, each with its own table, PK/salt,
`source_object_type`, `record_hash` behavior (where applicable), nested fields, and current-state vs
append-only mode. Known non-1:1 cases (e.g. workgroup-member native ids, the SQL-report endpoints) are mapped
individually, not forced into a universal mapping.

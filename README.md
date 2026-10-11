# KF Agent → PostgreSQL Ingestion (Part 2)

Consumes the existing **Part 1 KF Agent REST** JSON endpoints over HTTP and upserts the data into a
**configurable, separate** PostgreSQL schema that reuses the `iiq_native` table model. It does **not**
extract from SailPoint IdentityIQ and does **not** depend on `identityiq.jar`. It is an independent project —
Part 1 is untouched.

Pipeline: `Existing KF Agent HTTP endpoints → Part 2 → PostgreSQL`

Implemented entities:

| Command | Endpoint | Table | PK (uuid) | `source_object_type` | `record_hash` |
|---|---|---|---|---|---|
| `import-identity` | `GET /kfagent/identities` | `kf_identity` | `userid` | `sailpoint.object.Identity` | none (table has no column) |
| `import-entitlement` | `GET /kfagent/entitlements` | `kf_entitlement` | `entitlementid` | `sailpoint.object.ManagedAttribute` | Part 1's 33-field SHA-256 |
| `import-application` | `GET /kfagent/applications` | `kf_application` | `applicationid` | `sailpoint.object.Application` | SHA-256 over all 58 business fields |
| `import-account` | `GET /kfagent/accounts` | `kf_account` | `accountid` | `sailpoint.object.Link` | SHA-256 over all 29 business fields |

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

## Entitlement (`kf_entitlement`)
Same model as Identity, with its own Part 1 rules (`NativeManagedAttributeRepository`):
- `entitlementid` = `canonicalUuid(source_id)`; fallback `deterministicUuid("native-managedattribute|"+source_id)`,
  or `+name` only when `source_id` is null (Part 1's exact rule). Upsert `ON CONFLICT (entitlementid)`.
- 43 business columns = the 43 response fields, same names and table order (8 `jsonb`, 11 boolean, 4 timestamps).
- `record_hash` = SHA-256 over Part 1's **33-field subset** (it excludes `classifications*`, `member_attribute`,
  `full_name`, `is_group_type`, `is_inactive`, `is_auto_promotion`, `is_differencable`, `source_hash` and all
  lineage). `EntitlementParityTest` checks it, and the PK, against values produced by Part 1's own code.
- `source_hash` is IIQ's own hash from the response, stored as a plain business column (it is **not** `record_hash`).
- Lineage: `IdentityIQ` / `native_iiq_java_api` / `sailpoint.object.ManagedAttribute`, a fresh run id, `now()`,
  `is_deleted=false`. No deletion sweep yet (same as Identity).

## Application (`kf_application`)
Part 1 rules from `NativeApplicationRepository`:
- `applicationid` = `canonicalUuid(source_id)`; fallback `deterministicUuid("native-application|"+source_id)`
  (`+name` only when `source_id` is null). Upsert `ON CONFLICT (applicationid)`.
- 58 business columns = the 58 response fields, same names and order. `score` is `integer`, which needs a numeric
  value: a non-numeric score fails that row with a clear message instead of storing a wrong value. 10 `jsonb`
  columns (owners, remediators, dependencies, schemas, descriptions, attributes, 4 account filters). The
  rule/config columns stay `text`.
- `record_hash` = SHA-256 over **all 58** business fields. Parity with Part 1 is checked in `ApplicationTableTest`.
- Lineage: `IdentityIQ` / `native_iiq_java_api` / `sailpoint.object.Application`.

## Account (`kf_account`)
Part 1 rules from `NativeLinkRepository`:
- `accountid` = `canonicalUuid(source_id)`. If `source_id` is null, the fallback is
  `deterministicUuid("native-link|" + application_name + "|" + native_identity)`, the account's natural key.
  A null part becomes the text `null`, exactly as in Part 1. Upsert `ON CONFLICT (accountid)`.
- 29 business columns = the 29 response fields, same names and order: 5 `jsonb`, 7 boolean, 6 timestamps.
  `link_uuid` and `component_ids` stay `text`.
- `identity_id` / `application_id` are raw IIQ ids (text). `kf_account` has no foreign keys, so the import order
  doesn't matter.
- `record_hash` = SHA-256 over **all 29** business fields. Lineage: `IdentityIQ` / `native_iiq_java_api` / `sailpoint.object.Link`.

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
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_entitlement.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_application.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_account.sql
```
It writes to a **separate** schema and never alters an existing populated schema.

## Run
```bash
export KFAGENT_BASE_URL=http://127.0.0.1:8100
export PG_HOST=<host> PG_DATABASE=<db> PG_USERNAME=<user> PG_PASSWORD=<pw>
export PG_DEST_SCHEMA=iiq_native_part2
java -jar target/kfagent-ingest.jar import-identity
java -jar target/kfagent-ingest.jar import-entitlement
java -jar target/kfagent-ingest.jar import-application
java -jar target/kfagent-ingest.jar import-account
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

### Verify Entitlement
```sql
SELECT count(*) FROM iiq_native_part2_test.kf_entitlement;                        -- = response row count
SELECT entitlementid, source_id, application_name, value, record_hash, source_object_type, extraction_run_id
FROM iiq_native_part2_test.kf_entitlement LIMIT 5;
-- record_hash parity with Part 1 data for the same objects (should return 0 rows):
SELECT p.entitlementid FROM iiq_native_part2_test.kf_entitlement p
JOIN iiq_native.kf_entitlement o USING (entitlementid) WHERE p.record_hash <> o.record_hash;
```
Run it twice: the row count and `entitlementid`s stay the same, `record_hash` is unchanged for unchanged objects,
and `extraction_run_id` / `extracted_at` move to the latest run.

### Troubleshooting: `Unable to establish loopback connection`
On some Windows setups the JDK's networking (HttpClient) cannot create its internal AF_UNIX pipe under the
user temp directory. If a run fails with this message, point it at any short writable directory:
```bash
java -Djdk.net.unixdomain.tmpdir=D:/tmp -jar target/kfagent-ingest.jar import-entitlement
```
(The Maven tests already do this via surefire `argLine`.)

## Tests
```bash
mvn test
```
- `CanonicalIdsTest` — `userid`/UUID generation + deterministic fallback (pins Part 1 parity).
- `IdentitySqlTest` — generated DDL + `ON CONFLICT (userid)` upsert shape, column/placeholder counts, no `record_hash`.
- `EntitlementParityTest` — `record_hash` + `entitlementid` equal Part 1's own output (golden fixture produced by
  running Part 1's parser/repository code); excluded columns don't change the hash.
- `EntitlementRepositoryTest` — DDL column order = iiq_native.kf_entitlement, `ON CONFLICT (entitlementid)`, 49 params,
  binding of text/boolean/empty+nested jsonb/timestamps/NULLs, hash + lineage params, deterministic key across runs.
- `ApplicationTableTest` — `record_hash` + `applicationid` parity with Part 1 (golden fixture), every business field
  is in the hash, response keys = business columns, DDL order = iiq_native.kf_application, integer `score`
  binding/NULL/invalid handling, lineage params.
- `AccountTableTest` — `record_hash` + `accountid` parity with Part 1, including both fallback-key cases (natural
  key, and a null application name), every field hashed, DDL order = iiq_native.kf_account, binding, lineage.
- `EntityImportServiceTest` — real HTTP against an in-process mock KF Agent: `start`/`limit` paging until a short
  page, per-row failure isolation + reporting, clear error on a non-array/500 response.
- `IdentityBindingTest` — response→column mapping via a recording JDBC stub: canonical `userid`, text/boolean,
  nested JSON → `jsonb`, ISO timestamps, SQL NULL for null/absent fields, metadata params. No live DB needed.

## Status / next
Identity, Entitlement, Application and Account are done. Entitlement runs on the generic engine (`EntitySpec` + `EntityRepository` +
`EntityImportService`); further current-state entities are added as one `*Table` spec each. Identity is the complete first vertical slice (HTTP → parse → map → metadata → upsert → verification). The
column-spec + upsert engine is built to extend to the other ~39 endpoints, each with its own table, PK/salt,
`source_object_type`, `record_hash` behavior (where applicable), nested fields, and current-state vs
append-only mode. Known non-1:1 cases (e.g. workgroup-member native ids, the SQL-report endpoints) are mapped
individually, not forced into a universal mapping.

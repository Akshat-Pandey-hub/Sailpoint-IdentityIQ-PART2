# KF Agent → PostgreSQL Ingestion (Part 2)

A command-line tool that reads SailPoint IdentityIQ data from the **Part 1 KF Agent REST service** and loads it
into PostgreSQL. It does not connect to IdentityIQ directly and does not depend on `identityiq.jar`.

```
IdentityIQ → Part 1 (KF Agent REST, :8100/kfagent/*) → Part 2 (this tool) → PostgreSQL (iiq_native_part2)
```

The destination tables follow Part 1's `iiq_native` model (same columns, keys and `record_hash` rules).

## Requirements

- Java 17
- Maven 3.9+
- A running Part 1 KF Agent
- PostgreSQL with a user allowed to create tables in the destination schema

## Build

```bash
mvn clean package
```

Produces `target/kfagent-ingest.jar`. Run the tests with `mvn test`.

## Configuration

All settings come from environment variables (or `-D` JVM system properties). Nothing is hard-coded.

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `KFAGENT_BASE_URL` | yes¹ | — | KF Agent base URL, e.g. `http://127.0.0.1:8100` |
| `KFAGENT_USERNAME` / `KFAGENT_PASSWORD` | yes¹ | — | KF Agent Basic Auth: the same `IIQ_USERNAME` / `IIQ_PASSWORD` the KF Agent runs with |
| `PG_HOST`, `PG_DATABASE`, `PG_USERNAME`, `PG_PASSWORD` | yes | — | PostgreSQL connection |
| `PG_PORT` | no | `5432` | PostgreSQL port |
| `PG_DEST_SCHEMA` | no | `iiq_native_part2` | Destination schema |
| `KFAGENT_PAGE_SIZE` | no | `500` | Page size for paged endpoints |
| `HTTP_TIMEOUT_SECONDS` | no | `60` | HTTP timeout |
| `INCREMENTAL_OVERLAP_MINUTES` | no | `5` | Overlap applied to incremental watermarks |

¹ Not required by the database-only commands (`derive-native-events`, `derive-native-event-links`).
Without the KF Agent credentials, imports fail with `HTTP 401` (exit code 4).

Example (Windows CMD):

```bat
set KFAGENT_BASE_URL=http://127.0.0.1:8100
set KFAGENT_USERNAME=<IIQ username>
set KFAGENT_PASSWORD=<IIQ password>
set PG_HOST=localhost
set PG_PORT=5432
set PG_DATABASE=<database>
set PG_DEST_SCHEMA=iiq_native_part2
set PG_USERNAME=<user>
set PG_PASSWORD=<password>
```

## Usage

```bash
java -jar target/kfagent-ingest.jar <command> [--incremental]
```

Running without a command prints the full command list. Tables are created automatically on first run; the
equivalent DDL is in [`schema/`](schema) (`psql -v schema=iiq_native_part2 -f schema/<table>.sql`).

### Import commands

Each command reads one KF Agent route and writes one table.

| Group | Commands |
|---|---|
| Identity & access | `import-identity`, `import-entitlement`, `import-application`, `import-account`, `import-workgroup`, `import-role`, `import-identity-role`, `import-identity-entitlement`, `import-account-entitlement`, `import-role-entitlement`², `import-role-hierarchy`, `import-workgroup-member`, `import-group-definition` |
| Requests & provisioning | `import-identity-request`, `import-identity-request-item`, `import-identity-request-approval`, `import-provisioning-transaction`, `import-provisioning-item`, `import-work-item`, `import-workitem-archive`, `import-workflow-definition` |
| Certification & policy | `import-certification`, `import-certification-entity`, `import-certification-item`, `import-certification-archive`, `import-policy`, `import-policy-constraint`, `import-policy-violation` |
| History & operations | `import-audit-event`, `import-task-result`, `import-task-schedule`, `import-syslog-event`, `import-access-history`, `import-access-history-identity-event`, `import-access-history-certification`, `import-access-history-role-event` |
| SQL reports | `import-entitlement-assignment`, `import-workgroup-members`, `import-entitlement-certification`, `import-entitlement-certification-status` |

² Run `import-entitlement` first; role entitlements are linked to `kf_entitlement`.

**Write behaviour**

- **Entity tables** are upserted by their deterministic source key, so re-running an import never duplicates rows.
- **History tables** (audit events, archives, access history, syslog) are append-only.
- **SQL-report tables** are replaced in a single transaction, and only after every page has been fetched.

### Derived and governance commands

These read tables that are already loaded. They never call the KF Agent.

| Command | Writes | Notes |
|---|---|---|
| `reconcile-native-workitems` | `kf_reconciliation_finding` | Checks approval work-item references against `kf_workitem` and `kf_workitem_archive` |
| `derive-native-events` | `kf_event` | Append-only events from approvals, task results, provisioning transactions, work-item archives and certification decisions |
| `derive-native-event-links` | `kf_event_link` | Explicit links for events already in `kf_event`; run after `derive-native-events` |

Event and link ids are deterministic, so re-running is safe and never duplicates or changes stored rows.

## Incremental mode

`--incremental` is supported for `import-identity`, `import-entitlement`, `import-application`, `import-account`
and `import-role`. It fetches only objects modified after the stored watermark (`kf_extraction_watermark`) minus
the overlap window. The watermark advances only when a run has no failed rows, and incremental runs never perform
deletion sweeps. Full mode is the default.

## Deletion sweeps

For snapshot entities, a full import soft-deletes rows that are no longer returned by the source
(`is_deleted` / `deleted_at`), and revives them if they reappear. A sweep runs only when the snapshot is proven
complete against the KF Agent's source-count evidence; otherwise it is skipped and the command exits with `7`.
Append-only, report, derived and governance tables are never swept.

## Run ledger and lineage

Every command records one row in `kf_extraction_run`: status, timing, counts and errors. Its
`source_interface` is `kfagent-rest` for imports and `derived` for derived commands. Loaded rows carry the same
`extraction_run_id`, so data can be traced to the run that wrote it.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | Unexpected error |
| `2` | Usage error (unknown command or unsupported option) |
| `3` | Configuration error |
| `4` | KF Agent HTTP/source error |
| `5` | PostgreSQL error |
| `6` | Completed with row-level failures |
| `7` | Completeness not confirmed (sweep skipped, or syslog scan incomplete) |

## Project layout

```
src/main/java/com/keyforge/part2/
  Main.java          command dispatch
  config/            configuration
  http/              KF Agent client
  ingest/            entity specs, repositories, import and sweep services
  incremental/       watermarks and incremental imports
  ledger/            kf_extraction_run
  reconciliation/    reconciliation findings
  event/             kf_event and kf_event_link derivation
schema/              destination DDL, one file per table
```

# KF Agent → PostgreSQL Ingestion (Part 2)

A command-line tool that reads SailPoint IdentityIQ data from the **Part 1 KF Agent REST service** and loads it
into PostgreSQL. It does not connect to IdentityIQ directly and does not depend on `identityiq.jar`.

```
IdentityIQ → Part 1 (KF Agent REST, :8100/kfagent/*) → Part 2 (this tool) → PostgreSQL (iiq_native_part2)
```

The destination tables follow Part 1's `iiq_native` model (same columns, keys and `record_hash` rules).
Every command runs from the CLI or, unchanged, over HTTP in [REST mode](#rest-mode).

**Inventory:** 43 commands (40 imports and 3 derived commands), all reachable over REST, plus 45 tables:

| Tables | Count | Written by |
|---|---|---|
| Imported from the KF Agent | 40 | One import command each (see [Import commands](#import-commands)) |
| Derived from loaded tables | 3 | `kf_event`, `kf_event_link`, `kf_reconciliation_finding` (see [Derived commands](#derived-and-governance-commands)) |
| Internal metadata | 2 | `kf_extraction_run` (every run, see [Run ledger](#run-ledger-and-lineage)), `kf_extraction_watermark` (incremental runs) |

The 2 internal tables have no command of their own. They are written as a side effect of other commands.

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
`reconcile-native-workitems` never calls the KF Agent but still requires `KFAGENT_BASE_URL` to be set.
Without the KF Agent credentials, imports fail with `HTTP 401` (exit code 4). `start-rest` requires both credentials
(see [REST mode](#rest-mode)).

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

In full mode, each import reads one KF Agent route (`<KFAGENT_BASE_URL>/kfagent/<route>`) and writes one table. Over
REST, every command is `GET /kfagent-ingest/<command>`.

Write modes:
- **upsert:** rows are upserted by their deterministic source key, so re-running never duplicates rows.
- **append:** append-only; rows that already exist are skipped.
- **replace:** a SQL report. The table is replaced in one transaction, after every page has been fetched.
- **sweep:** the table also gets the [deletion sweep](#deletion-sweeps).

Incremental modes are explained in [Incremental mode](#incremental-mode):
- **changed:** rows changed since the watermark.
- **family:** the parent plus its children, under one watermark.
- **created:** rows created since the watermark.
- **via parent:** no `--incremental` of its own; refreshed by the parent's incremental run.
- **—:** full mode only.

| Command | KF Agent route | Table | Write | Incremental |
|---|---|---|---|---|
| `import-identity` | `identities` | `kf_identity` | upsert | changed |
| `import-entitlement` | `entitlements` | `kf_entitlement` | upsert | changed |
| `import-application` | `applications` | `kf_application` | upsert | changed |
| `import-account` | `accounts` | `kf_account` | upsert | changed |
| `import-workgroup` | `workgroups` | `kf_workgroup` | upsert | — |
| `import-role` | `roles` | `kf_role` | upsert | changed |
| `import-identity-role` | `identity-roles` | `kf_identity_role` | upsert, sweep | — |
| `import-identity-entitlement` | `identity-entitlements` | `kf_identity_entitlement` | upsert, sweep | changed |
| `import-account-entitlement` | `account-entitlements` | `kf_account_entitlement` | upsert, sweep | family (+ `kf_account`) |
| `import-role-entitlement`² | `role-entitlements` | `kf_role_entitlement` | upsert, sweep | — |
| `import-role-hierarchy` | `role-hierarchy` | `kf_role_hierarchy` | upsert, sweep | — |
| `import-identity-request` | `identity-requests` | `kf_identity_request` | upsert, sweep | family (+ items, approvals) |
| `import-identity-request-item` | `identity-request-items` | `kf_identity_request_item` | upsert, sweep | via parent |
| `import-identity-request-approval` | `identity-request-approvals` | `kf_identity_request_approval` | upsert, sweep | via parent |
| `import-policy` | `policies` | `kf_policy` | upsert | — |
| `import-provisioning-item` | `provisioning-items` | `kf_provisioning_item` | upsert, sweep | via parent |
| `import-provisioning-transaction` | `provisioning-transactions` | `kf_provisioning_txn` | upsert, sweep | family (+ items) |
| `import-work-item` | `work-items` | `kf_workitem` | upsert, sweep | changed |
| `import-workflow-definition` | `workflow-definitions` | `kf_workflow_definition` | upsert | — |
| `import-certification` | `certifications` | `kf_certification` | upsert, sweep | — |
| `import-certification-entity` | `certification-entities` | `kf_certification_entity` | upsert, sweep | changed |
| `import-certification-item` | `certification-items` | `kf_certification_item` | upsert, sweep | changed |
| `import-certification-archive` | `certification-archives` | `kf_certification_archive` | append | created |
| `import-policy-constraint` | `policy-constraints` | `kf_policy_constraint` | upsert, sweep | — |
| `import-policy-violation` | `policy-violations` | `kf_violation` | upsert | — |
| `import-entitlement-assignment` | `entitlement-assignment` | `kf_entitlement_assignment` | replace | — |
| `import-workgroup-member` | `workgroupmember` | `kf_workgroup_member` | upsert | — |
| `import-entitlement-certification` | `entitlement-certification` | `kf_entitlement_certification` | replace | — |
| `import-entitlement-certification-status` | `entitlement-certification-status` | `kf_entitlement_certification_status` | replace | — |
| `import-workgroup-members` | `workgroup-members` | `kf_workgroup_members` | replace | — |
| `import-audit-event` | `audit-events` | `kf_audit_event` | append | created |
| `import-task-result` | `task-results` | `kf_task_result` | upsert | changed |
| `import-task-schedule` | `task-schedules` | `kf_task_schedule` | upsert | — |
| `import-workitem-archive` | `workitem-archives` | `kf_workitem_archive` | append | created |
| `import-group-definition` | `group-definitions` | `kf_group_definition` | upsert | — |
| `import-access-history` | `access-history` | `kf_access_hist_entitlement` | append | created |
| `import-access-history-identity-event` | `access-history/identity-events` | `kf_access_hist_identity_event` | append | created |
| `import-access-history-certification` | `access-history/certifications` | `kf_access_hist_certification` | append | created |
| `import-access-history-role-event` | `access-history/role-events` | `kf_access_hist_role_event` | append | created |
| `import-syslog-event` | `syslog-events` (paged, complete-scan guarded) | `kf_syslog_event` | append | created |

² Run `import-entitlement` first; role entitlements are linked to `kf_entitlement`.

Two pairs of names differ by one letter:
- `import-workgroup-member` is the native workgroup-membership edge.
- `import-workgroup-members` is the SQL report.

### Derived and governance commands

These read tables that are already loaded. They never call the KF Agent, and they run in full mode only:
`--incremental` is rejected with exit code 2.

| Command | Reads | Writes |
|---|---|---|
| `reconcile-native-workitems` | `kf_identity_request_approval`, `kf_workitem`, `kf_workitem_archive` | `kf_reconciliation_finding` (one finding per run: approval work-item references matched against live and archived work items) |
| `derive-native-events` | `kf_identity_request_approval`, `kf_task_result`, `kf_provisioning_txn`, `kf_workitem_archive`, `kf_certification_item` | `kf_event` (append-only) |
| `derive-native-event-links` | The same source tables, plus `kf_event` | `kf_event_link` (append-only; only links whose event is already in `kf_event`) |

Order:
1. Import the source tables listed above.
2. Run `reconcile-native-workitems` and `derive-native-events`.
3. Run `derive-native-event-links`.

A missing source table does not fail the command. The reconciliation finding is recorded as `SKIPPED` with the reason,
and the derive commands report the missing sources and derive nothing from them. Event and link ids are
deterministic, so re-running is safe and never duplicates or changes stored rows.

## Incremental mode

`--incremental` fetches only what changed since the last successful run, using a per-entity watermark in
`kf_extraction_watermark` minus the overlap window (`INCREMENTAL_OVERLAP_MINUTES`, default 5):

| Commands | Follows |
|---|---|
| `import-identity`, `import-entitlement`, `import-application`, `import-account`, `import-role`, `import-work-item`, `import-task-result`, `import-certification-item`, `import-certification-entity`, `import-identity-entitlement` | IIQ `modified`, or `created` for never-modified objects (watermark `max(coalesce(modified_at, created_at))`) |
| `import-identity-request` (also refreshes request items and approvals), `import-provisioning-transaction` (also refreshes provisioning items), `import-account-entitlement` (also refreshes the changed accounts) | The parent's change time, under one shared watermark that advances only if the parent and every child loaded without a failure |
| `import-audit-event`, `import-syslog-event`, `import-workitem-archive`, `import-certification-archive`, `import-access-history`, `import-access-history-identity-event`, `import-access-history-certification`, `import-access-history-role-event` | IIQ `created` (watermark `max(created_at)`) |

That makes 21 commands with `--incremental`.

Request items, approvals and provisioning items have no change time of their own. For these three commands,
`--incremental` is rejected with exit code 2 and a hint to use the parent's command. Without the flag they run as
ordinary full imports.

The other 16 imports and the 3 derived commands run in full mode only. For them, `--incremental` is a usage error
(exit code 2) and nothing runs. The 16 imports are:
- the 4 SQL reports
- `import-workgroup`, `import-identity-role`, `import-role-entitlement`, `import-role-hierarchy`
- `import-policy`, `import-policy-constraint`, `import-policy-violation`
- `import-workflow-definition`, `import-certification`, `import-workgroup-member`, `import-task-schedule`,
  `import-group-definition`

Further rules:
- The first incremental run of an entity is a full fetch that sets the baseline.
- The watermark advances only when a run has no failed rows. For syslog, it also requires a scan confirmed complete.
- Incremental runs never perform deletion sweeps, so keep running full imports periodically.
- Full mode is the default.

## Deletion sweeps

A full import of the 15 tables marked **sweep** above soft-deletes rows that the source no longer returns
(`is_deleted` / `deleted_at`), and revives rows that reappear. These are the tables whose KF Agent route reports a
verified source count. They are listed in `SweepPolicies.CONFIGS`.

A sweep runs only when the snapshot is proven complete against that count. Otherwise the sweep is skipped and the
command exits with `7`.

All other tables are never swept. That includes the upsert tables without a verified count (identity, entitlement,
application, account, workgroup, role, policy, policy violation, task result and schedule, group definition, workflow
definition, workgroup member). Rows of these tables that are deleted in IdentityIQ stay active in PostgreSQL.
Append-only, report, derived and internal tables are never swept either.

## Run ledger and lineage

Every command run records one row in `kf_extraction_run`: status, timing, counts and errors. Usage errors
(exit code 2) and `start-rest` itself are not recorded. Its
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

## REST mode

`java -jar target/kfagent-ingest.jar start-rest` starts an HTTP server that runs the same commands on request.
`GET /kfagent-ingest/<command>` runs `<command>` exactly as the CLI does. It goes through the same dispatcher and run
ledger, in the same process. `<command>` is any of the 43 commands above; `start-rest` is not a route.

The only query parameter is `mode`:
- `?mode=incremental` is the same as `--incremental`.
- `?mode=full` is the same as no flag, which is the default.

Any other parameter gets `400`. The run is recorded in `kf_extraction_run` as usual. The response is sent when the command finishes
and contains its JSON result: run id, exit code, status, counts and error. `GET /kfagent-ingest` lists the commands.

| Setting | Default | Meaning |
|---|---|---|
| `REST_HOST` | `127.0.0.1` | Bind address. Local only by default. |
| `REST_PORT` | `8200` | Port. |

**Authentication:** every request must use HTTP Basic Auth with the existing `KFAGENT_USERNAME` and
`KFAGENT_PASSWORD`, the same credentials Part 2 already sends to the KF Agent. Both are required to start REST mode.
In Postman, open the Authorization tab, choose Basic Auth, and enter those values. Missing or wrong credentials get
`401` on every route. The password is never logged or returned.

The KF Agent and PostgreSQL settings are the same as for the CLI.

- **HTTP status:** `200` means the command ran. Check `status`/`success`: exit 6 is `COMPLETED_WITH_FAILURES` and
  exit 7 is `INCOMPLETE`, both with `success: false`. Other codes: `400` bad request or rejected option (exit 2),
  `401` missing or wrong credentials, `404` unknown route, `409` another command is running, `502` KF Agent error (exit 4), `500` other
  failures (exits 1, 3 and 5).
- **One command at a time.** A request that arrives during a run gets `409`, and nothing is started. This applies only
  within this server: do not run CLI imports into the same schema at the same time.
- **No time or record limit.** Large imports keep the request open until they finish. If the caller, a proxy or a
  tunnel times out first, the command still runs to completion and is recorded in `kf_extraction_run`.
- **Remote access:** the server speaks plain HTTP, and Basic Auth sends the password with every request, encoded but
  not encrypted. Only bind a non-loopback `REST_HOST` behind HTTPS termination (a reverse proxy or a TLS tunnel).
  Never expose the port directly.

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

# KF Agent → PostgreSQL Ingestion (Part 2)

## What this project is

Part 2 fetches SailPoint IdentityIQ data through the existing **Part 1 KF Agent REST services**, processes it and
stores it in a PostgreSQL schema (default `iiq_native_part2`). It never connects to IdentityIQ directly.

Every import, plus the derived-data and reconciliation commands, can be run in two ways:
- from the command line
- through the Part 2 REST service, which runs exactly the same code

```
IdentityIQ → Part 1 KF Agent (/kfagent/*) → Part 2 REST service / import logic → PostgreSQL
```

The tables follow Part 1's `iiq_native` model and are created automatically on first use.

## How we run and use it

**1. Build the JAR and set the environment variables** (Windows CMD):

```bat
mvn package

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

The KF Agent credentials serve two purposes:
- Part 2 uses them to call the KF Agent.
- The REST service uses them to check every incoming request.

Optional settings and their defaults:
- `REST_HOST` (`127.0.0.1`) and `REST_PORT` (`8200`): where the REST service listens.
- `KFAGENT_PAGE_SIZE` (`500`) and `HTTP_TIMEOUT_SECONDS` (`60`): paging and timeout for KF Agent calls.

**2. Start the REST service:**

```bat
java -jar target/kfagent-ingest.jar start-rest
```

**3. Call it from Postman:**

```
GET http://127.0.0.1:8200/kfagent-ingest/import-identity
Authorization tab → Basic Auth → KFAGENT_USERNAME / KFAGENT_PASSWORD
```

**4. What happens:**
- Part 2 runs the `import-identity` command.
- It fetches every page of `/kfagent/identities` from the KF Agent.
- It processes the rows and upserts them into `kf_identity`.
- It records the run in `kf_extraction_run`.

The request waits until the import finishes. Only one command runs at a time; a second request during a run gets
`409`. `GET http://127.0.0.1:8200/kfagent-ingest` lists all routes.

**5. Reading the response:**

```json
{ "command": "import-identity", "mode": "full", "exit_code": 0, "status": "SUCCESS", "success": true,
  "run_id": "…", "extracted": 68, "inserted": 0, "updated": 68, "failed": 0, "entity_counts": [ … ] }
```

| `status` | Meaning |
|---|---|
| `SUCCESS` (`success: true`) | Everything was loaded. |
| `COMPLETED_WITH_FAILURES` | Some rows failed. The response is HTTP 200, but `success` is `false`. |
| `INCOMPLETE` | Completeness could not be confirmed. The response is HTTP 200, but `success` is `false`. |
| `FAILED` | The command failed. The `error` field gives the reason. |

Other HTTP codes: `401` wrong credentials, `404` unknown route, `502` KF Agent error.

The same commands also run directly from the command line: `java -jar target/kfagent-ingest.jar <command>`.

## REST services and the data they store

Every route is `GET /kfagent-ingest/<command>`, where `<command>` is the CLI command name shown below. Each import
fetches from the matching KF Agent endpoint and writes the table shown.

### Caller-controlled fetch scope

Import routes accept source-side fetch options for entity resources that support them. `start` is a zero-based offset
and must be supplied together with `limit`; that request imports exactly one Part 1 response page and does not continue
to later offsets. `REST_MAX_FETCH_LIMIT` caps caller page size (default `10000`). For example:

```http
GET /kfagent-ingest/import-identity?start=0&limit=100
GET /kfagent-ingest/import-identity?start=100&limit=100
GET /kfagent-ingest/import-identity?name=spadmin&inactive=false
```

Without `start`/`limit`, supported filters and a caller-supplied `modifiedAfter` are forwarded to Part 1 and Part 2
imports the single response Part 1 returns using that endpoint's defaults; Part 2 does not request later offsets.
Callers that need more matching rows should request successive explicit pages. Filters use Part 1's exact scalar-field
matching semantics and are ANDed; structured JSON and technical fields are rejected. Filter names are validated per
command, and values are URL-encoded when Part 2 calls Part 1. `modifiedAfter` is accepted only for commands whose Part 1
resource supports it. It is a caller-bounded fetch and does not update Part 2's managed watermark.

These caller-scoped requests reuse the existing entity writers but are always partial views: they never run a deletion
sweep or advance a watermark. Omit all fetch options to retain the existing full-import path, including its established
paged import loops and verified-snapshot behavior. `mode=incremental` continues to use Part 2's stored watermark and
overlap rules; it cannot be combined with explicit caller fetch options. SQL-report routes reject caller scope options:
their current table has a generated row-number key and only supports a transactional full replace, so an upsert of a
partial report row cannot be made safe from the available fields.

**Identity and access**

| Command | Fetches | Writes |
|---|---|---|
| `import-identity` | Identities (users) | `kf_identity` |
| `import-application` | Applications | `kf_application` |
| `import-account` | Accounts on applications | `kf_account` |
| `import-entitlement` | Entitlement catalog | `kf_entitlement` |
| `import-role` | Roles | `kf_role` |
| `import-role-hierarchy` | Role inheritance/relationships | `kf_role_hierarchy` |
| `import-role-entitlement` | Entitlements granted by roles (run `import-entitlement` first) | `kf_role_entitlement` |
| `import-identity-role` | Roles assigned to identities | `kf_identity_role` |
| `import-identity-entitlement` | Entitlements held by identities | `kf_identity_entitlement` |
| `import-account-entitlement` | Entitlements and permissions on accounts | `kf_account_entitlement` |
| `import-workgroup` | Workgroups | `kf_workgroup` |
| `import-workgroup-member` | Workgroup ↔ identity memberships | `kf_workgroup_member` |
| `import-group-definition` | Populations and group definitions | `kf_group_definition` |

**Requests, provisioning and work items**

| Command | Fetches | Writes |
|---|---|---|
| `import-identity-request` | Access requests | `kf_identity_request` |
| `import-identity-request-item` | Items within requests | `kf_identity_request_item` |
| `import-identity-request-approval` | Approval steps of requests | `kf_identity_request_approval` |
| `import-provisioning-transaction` | Provisioning transactions | `kf_provisioning_txn` |
| `import-provisioning-item` | Attribute/permission changes within transactions | `kf_provisioning_item` |
| `import-work-item` | Open work items | `kf_workitem` |
| `import-workitem-archive` | Completed (archived) work items | `kf_workitem_archive` |
| `import-workflow-definition` | Workflow definitions | `kf_workflow_definition` |

**Certifications and policies**

| Command | Fetches | Writes |
|---|---|---|
| `import-certification` | Certification campaigns | `kf_certification` |
| `import-certification-entity` | Identities/entities under certification | `kf_certification_entity` |
| `import-certification-item` | Individual certification items and decisions | `kf_certification_item` |
| `import-certification-archive` | Archived certifications | `kf_certification_archive` |
| `import-policy` | Policies | `kf_policy` |
| `import-policy-constraint` | Rules/constraints within policies | `kf_policy_constraint` |
| `import-policy-violation` | Policy violations | `kf_violation` |

**History and operations** (append-only: existing rows are kept, new ones added)

| Command | Fetches | Writes |
|---|---|---|
| `import-audit-event` | Audit events | `kf_audit_event` |
| `import-syslog-event` | System log events (paged, checked for completeness) | `kf_syslog_event` |
| `import-task-result` | Task results | `kf_task_result` |
| `import-task-schedule` | Task schedules | `kf_task_schedule` |
| `import-access-history` | Historical entitlement captures | `kf_access_hist_entitlement` |
| `import-access-history-identity-event` | Historical identity events | `kf_access_hist_identity_event` |
| `import-access-history-role-event` | Historical role events | `kf_access_hist_role_event` |
| `import-access-history-certification` | Historical certifications | `kf_access_hist_certification` |

`kf_task_result` and `kf_task_schedule` are upserted, not appended.

**SQL reports** (the whole table is replaced in one transaction on each run)

| Command | Fetches | Writes |
|---|---|---|
| `import-entitlement-assignment` | Who holds which entitlement, with identity/account details | `kf_entitlement_assignment` |
| `import-entitlement-certification` | Certification history of each identity entitlement | `kf_entitlement_certification` |
| `import-entitlement-certification-status` | Latest certification status of each identity entitlement | `kf_entitlement_certification_status` |
| `import-workgroup-members` | Workgroup membership report | `kf_workgroup_members` |

**Derived data and reconciliation** (these read tables already loaded; they don't call the KF Agent)

| Command | Does | Writes |
|---|---|---|
| `reconcile-native-workitems` | Checks that the work items referenced by request approvals exist in `kf_workitem` or `kf_workitem_archive` | `kf_reconciliation_finding` |
| `derive-native-events` | Derives events from approvals, task results, provisioning transactions, archived work items and certification items | `kf_event` |
| `derive-native-event-links` | Links those events to the objects they involve (run after `derive-native-events`) | `kf_event_link` |

## Incremental mode

Incremental mode is implemented for supported commands only, not for every command. A run fetches only the records
changed since the last successful run. The start point is that run's stored watermark (in `kf_extraction_watermark`),
moved back by an overlap window (`INCREMENTAL_OVERLAP_MINUTES`, default 5).

```
CLI:  java -jar target/kfagent-ingest.jar import-identity --incremental
REST: GET http://127.0.0.1:8200/kfagent-ingest/import-identity?mode=incremental
```

- **Unsupported commands:** requesting incremental mode is rejected and nothing runs.
- **First run:** the first incremental run of a command fetches everything and sets the baseline.
- **No deletion sweeps:** incremental runs never mark deleted rows. Full mode, the default, is still needed
  periodically.

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
| `import-workgroup` | `GET /kfagent/workgroups` | `kf_workgroup` | `workgroupid` | `sailpoint.object.Identity` | SHA-256 over all 16 business fields |
| `import-role` | `GET /kfagent/roles` | `kf_role` | `roleid` | `sailpoint.object.Bundle` | SHA-256 over all 33 fields |
| `import-identity-role` | `GET /kfagent/identity-roles` | `kf_identity_role` | `identityroleid` (composite edge key) | `sailpoint.object.Identity.roles` | 18 of 19 fields (not `identity_name`) |
| `import-identity-entitlement` | `GET /kfagent/identity-entitlements` | `kf_identity_entitlement` | `entitlementid` (IdentityEntitlement id) | `sailpoint.object.IdentityEntitlement` | 30 of 33 fields |
| `import-account-entitlement` | `GET /kfagent/account-entitlements` | `kf_account_entitlement` | `accountentitlementid` (type-dependent edge key) | `sailpoint.object.Link.entitlementAttributes` / `.permissions` / `.targetPermissions` (per row) | 14 of 16 fields |
| `import-role-entitlement` | `GET /kfagent/role-entitlements` | `kf_role_entitlement` | `roleentitlementid` (from derived natural key) | `sailpoint.object.Bundle` | Part 1's 17-field hash incl. resolved `entitlement_id` |
| `import-role-hierarchy` | `GET /kfagent/role-hierarchy` | `kf_role_hierarchy` | `rolehierarchyid` (from derived natural key) | `sailpoint.object.Bundle` | Part 1's 5-field hash (own key names) |
| `import-identity-request` | `GET /kfagent/identity-requests` | `kf_identity_request` | `identityrequestid` (no fallback) | `sailpoint.object.IdentityRequest` | SHA-256 over all 34 business fields |
| `import-identity-request-item` | `GET /kfagent/identity-request-items` | `kf_identity_request_item` | `identityrequestitemid` (the item's own id) | `sailpoint.object.IdentityRequestItem` | SHA-256 over all 33 business fields |
| `import-identity-request-approval` | `GET /kfagent/identity-request-approvals` | `kf_identity_request_approval` | `identityrequestapprovalid` (derived: request, work item, owner, index) | `sailpoint.object.WorkflowSummary$ApprovalSummary` | SHA-256 over all 17 business fields |
| `import-policy` | `GET /kfagent/policies` | `kf_policy` | `policyid` | `sailpoint.object.Policy` | SHA-256 over all 21 fields |
| `import-provisioning-item` | `GET /kfagent/provisioning-items` | `kf_provisioning_item` | `provisioningitemid` (derived per item) | `ProvisioningPlan$AttributeRequest` / `$PermissionRequest` (per row) | 16 of 17 fields (not `instance`) |

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

## Workgroup (`kf_workgroup`)
Part 1 rules from `NativeWorkgroupRepository`:
- `workgroupid` = `canonicalUuid(source_id)`; fallback `deterministicUuid("native-workgroup|"+source_id)`
  (`+name` only when `source_id` is null). Upsert `ON CONFLICT (workgroupid)`.
- 16 business columns = the 16 response fields, same names and order: 2 `jsonb` (`capabilities`, `attributes`),
  2 boolean, 2 timestamps.
- `source_object_type` is `sailpoint.object.Identity`. In IIQ a workgroup **is** an Identity with
  `workgroup=true`, and Part 1 stamps it that way. Because of that, a workgroup's `workgroupid` has the same
  UUID form as an identity `userid` would.
- `record_hash` = SHA-256 over **all 16** business fields.

## Role (`kf_role`)
Part 1 rules from `NativeRoleRepository`:
- `roleid` = `canonicalUuid(source_id)`; fallback `deterministicUuid("native-role|"+source_id)`
  (`+name` only when `source_id` is null). Upsert `ON CONFLICT (roleid)`.
- 33 response fields. 27 sit before `record_hash`. 6 (`role_type_definition`, `applications`,
  `monitored_applications`, `scorecard`, `selector_summary`, `selector`) sit after `deleted_at`, as in
  iiq_native.kf_role (Part 1 added them with `ALTER TABLE`). `EntitySpec.trailing` reproduces that layout.
- Only `descriptions`, `attributes` and `selector` are `jsonb`. The other 5 Part 1-coded fields are `text` in
  Part 1 and stay text here, even when they contain JSON-looking strings. `risk_score_weight` is `integer`.
- `record_hash` = SHA-256 over **all 33** fields. Lineage: `IdentityIQ` / `native_iiq_java_api` / `sailpoint.object.Bundle`.

## Identity-Role (`kf_identity_role`)
One row per identity-to-role edge (`relationship_type` ASSIGNED or DETECTED). Part 1 rules from `NativeIdentityRoleRepository`:
- The response has **no `source_id`**, because an edge is not a standalone IIQ object. The key is **not** the
  identity id or the role id. `identityroleid` = `deterministicUuid("native-identity-role|" + identity_id + "|" + role_id + "|"
  + relationship_type + "|" + assignment_id)`, always name-based, with a null part becoming the text `null` as
  in Part 1. So the same identity and role with a different relationship type or assignment is a different edge.
  Upsert `ON CONFLICT (identityroleid)`.
- 19 business columns = the 19 response fields, same names and order. `targets` is the only `jsonb`;
  `detection_assignment_ids` is text. `identity_id` / `role_id` are raw IIQ ids and there are no foreign keys.
- `record_hash` = Part 1's **18-field** subset: every field except `identity_name`.
- Lineage: `IdentityIQ` / `native_iiq_java_api` / `sailpoint.object.Identity.roles`.

## Identity-Entitlement (`kf_identity_entitlement`)
The identity-to-entitlement relationship with provenance (IIQ `IdentityEntitlement`). Part 1 rules from
`NativeIdentityEntitlementRepository`. They differ from identity-roles:
- This edge **is** a real IIQ object, so the response has a `source_id`. The PK column is named `entitlementid`, the
  same name as in `kf_entitlement`, but here it identifies the IdentityEntitlement row, not the catalog entitlement.
  It is `canonicalUuid(source_id)`. If `source_id` is null or not a GUID, the fallback is the composite natural key
  `deterministicUuid("native-identity-entitlement|" + identity_id + "|" + application_name + "|" + native_identity + "|"
  + attribute_name + "|" + attribute_value)`. `source_id` itself is not part of the fallback, and a null part becomes
  the text `null`. Upsert `ON CONFLICT (entitlementid)`.
- 33 business columns = the 33 response fields, same names and order. `value_list` is the only `jsonb`;
  `source_assignable_roles` / `source_detected_roles` are text.
- `record_hash` = Part 1's **30-field** subset: every field except `identity_name`, `application_id` and `display_name`.
- Lineage: `IdentityIQ` / `native_iiq_java_api` / `sailpoint.object.IdentityEntitlement`.

## Account-Entitlement (`kf_account_entitlement`)
Entitlement values and permissions literally present on an account (Link). Part 1 rules from
`NativeAccountEntitlementRepository` / `NativeAccountEntitlementMapper`:
- `type` names the edge kind: `ATTRIBUTE` (an entitlement attribute value), `PERMISSION`, or `TARGET_PERMISSION`.
- There is no `source_id`. `accountentitlementid` is name-based, and **the formula depends on the type** (a null
  type counts as ATTRIBUTE; a null part becomes the text `null`):
  - ATTRIBUTE: `deterministicUuid("native-account-entitlement|" + link_id + "|" + attribute_name + "|" + attribute_value)`
  - PERMISSION / TARGET_PERMISSION: `deterministicUuid("native-account-entitlement|" + link_id + "|" + type + "|"
    + permission_target + "|" + permission_rights + "|" + permission_annotation)`
- `source_object_type` is **per row**, as Part 1's mapper stamps it: `sailpoint.object.Link.entitlementAttributes`,
  `sailpoint.object.Link.permissions` or `sailpoint.object.Link.targetPermissions`. A type Part 1 never produces fails
  that row with a clear message, so no lineage value is guessed.
- 16 response fields, all text except `permission_rights_list` / `permission_attributes` (`jsonb`), and no
  timestamps. `type` and the 6 `permission_*` columns sit after `deleted_at`, as in iiq_native (`EntitySpec.trailing`).
- `record_hash` = Part 1's **14-field** subset: every field except `identity_name` and `application_id`.

## Role-Entitlement (`kf_role_entitlement`)
What each role grants: Bundle profile constraints (`PROFILE_CONSTRAINT`) and permissions (`PROFILE_PERMISSION`).
This is a **non-1:1** table. Part 1 persists columns the KF Agent response leaves out, so Part 2 **derives** them
exactly as Part 1 does (`NativeRoleEntitlementRepository` + `NativeRoleRelationshipMapper.naturalKey`). It uses a
dedicated `RoleEntitlementRepository` (an `EntityWriter`), not a generic spec.
- `src_natural_key` = Part 1's length-prefixed key over source_role_id, profile_ordinal, entitlement_type,
  constraint_path, application_id, attribute_name, filter_operation, filter_value, permission_target,
  permission_rights, permission_rights_list and permission_annotation.
- `roleentitlementid` = `deterministicUuid("native-role-entitlement|" + src_natural_key)`. Upsert `ON CONFLICT (roleentitlementid)`.
- `role_id` = canonical(`source_role_id`), with fallback `deterministicUuid("native-role|" + id)`. `src_object_id` = `source_role_id`.
- `entitlement_id` is linked **only** for a `PROFILE_CONSTRAINT` with an `EQ` filter whose (application, attribute, value)
  matches **exactly one** row of `<PG_DEST_SCHEMA>.kf_entitlement`. Otherwise it is NULL (Part 1: "do not guess").
  **Run `import-entitlement` first**: the command refuses to start if `kf_entitlement` is missing from the schema.
- `record_hash` = Part 1's 17-field hash. It includes the linked `entitlement_id`, so the hash changes if the link
  changes, and it renders `filter_value` / `permission_rights_list` the way Part 1 does.
- As in Part 1, a null `filter_value` is stored as JSON `null` and `permission_rights_list` is never NULL (`[]`).
- Checked against real data: the one live row reproduces the row Part 1 stored in `iiq_native` exactly (key, natural
  key, hash). One limit: the natural key includes each scalar filter value's Java class, computed inside IIQ before
  JSON. An IIQ filter value held as `Long`, `Float` or `BigDecimal` would come back as `Integer` / `Double`, giving a
  different key from Part 1's. Strings, booleans, integers, doubles, lists and maps (the normal cases) are exact,
  and no current row has a numeric filter value.

## Role-Hierarchy (`kf_role_hierarchy`)
Role-to-role edges: `INHERITANCE`, `REQUIREMENT`, `PERMIT`. This is also **non-1:1**. The response carries only 5 text
fields, and Part 2 derives the rest as Part 1 does (`NativeRoleHierarchyRepository` + the mapper's `addHierarchy`),
using a dedicated `RoleHierarchyRepository`.
- **Direction:** the owning role is `source_role_id` / `role_name`; the inherited, required or permitted role is
  `related_role_source_id` / `related_role_name`. Raw IIQ Bundle ids are stored as-is.
- `src_natural_key` = `part(source_role_id) + part(relationship_type) + part(related_role_source_id)`, where
  `part(v)` = `length:value` and null = `-1:`. `rolehierarchyid` = `deterministicUuid("native-role-hierarchy|" + src_natural_key)`.
  Names are not part of the key, while direction and type are: the same two roles with another type, or the
  reverse direction, are different edges.
- `role_id` / `related_role_id` (uuid) = canonical UUID of each Bundle id, with fallback `deterministicUuid("native-role|" + id)`.
  `src_object_id` = `source_role_id`.
- `record_hash` = Part 1's 5-field hash with **its own key names**: `source_role_name` for `role_name`, and
  `related_role_id` holding the **raw** related Bundle id, not the uuid column.
- Checked against real data: the one live edge reproduces the row Part 1 stored in `iiq_native` exactly (key, natural
  key, hash, both role uuids).

## Identity-Request (`kf_identity_request`)
Part 1 rules from `NativeIdentityRequestRepository`:
- `identityrequestid` = `canonicalUuid(source_id)` with **no fallback**. Part 1 refuses a request without a usable IIQ
  id, so such a row **fails** with a clear message instead of getting an invented key. `name` is IIQ's request number
  (e.g. `0000000008`); it stays text and is not the key. Upsert `ON CONFLICT (identityrequestid)`.
- 34 business columns = the 34 response fields, same names and order. `errors` is the only `jsonb`;
  `item_count` / `approval_count` are `integer`; 8 booleans and 4 timestamps.
- `record_hash` = SHA-256 over **all 34** fields. Lineage: `IdentityIQ` / `native_iiq_java_api` / `sailpoint.object.IdentityRequest`.
- The request's items and approvals are separate entities with their own endpoints and tables; they are not in this
  response.
- Checked against real data: for all 45 live requests, Part 2's `identityrequestid` and `record_hash` equal what Part 1
  stored in `iiq_native` (45/45).

## Identity-Request-Item (`kf_identity_request_item`)
Part 1 rules from `NativeIdentityRequestItemRepository` / `NativeIdentityRequestParser.toItem`:
- `identityrequestitemid` = the **item's own** IIQ id, `canonicalUuid(source_id)`. The fallback is
  `deterministicUuid("native-identity-request-item|" + source_id)`, and a null id becomes the text `null`, as in Part 1.
  The parent request is **not** the key: two items of one request have different keys, and an item keeps its key if
  its parent fields change. Upsert `ON CONFLICT (identityrequestitemid)`.
- **Parent link:** `request_source_id` (the raw IIQ IdentityRequest id) and `request_name` (the parent request number),
  both text. Part 1 fills in `request_source_id` from the parent before the response is built, so the response always
  carries it. There is no foreign key, as in iiq_native. To join to `kf_identity_request`, use
  `kf_identity_request.source_id = kf_identity_request_item.request_source_id`.
- 33 business columns = the 33 response fields, same names and order, all scalar (no jsonb). `retries` is `integer`;
  there are 9 booleans and 4 timestamps.
- `record_hash` = SHA-256 over **all 33** fields. Lineage: `IdentityIQ` / `native_iiq_java_api` / `sailpoint.object.IdentityRequestItem`.
- Checked against real data: for all 186 live items, Part 2's key, parent link and `record_hash` equal what Part 1 stored
  in `iiq_native` (186/186 each).

## Identity-Request-Approval (`kf_identity_request_approval`)
The approval summaries of a request (`WorkflowSummary$ApprovalSummary`). Part 1 rules from
`NativeIdentityRequestApprovalRepository` / `NativeIdentityRequestParser.toApproval`:
- An approval summary has **no IIQ id of its own**, so the key is **not** the parent request id.
  `identityrequestapprovalid` = `deterministicUuid("native-identity-request-approval|" + request_source_id + "|" + work_item_id
  + "|" + owner + "|" + approval_index)`, always name-based, with a null part (including a null index) becoming the text
  `null`, as in Part 1. Changing the decision (state, approved, completer) keeps the key; another index is another approval.
- **Links:** to the parent **request** via `request_source_id` (Part 1 fills it from the parent before the response is
  built) and `request_name`; to the approval **work item** via `work_item_id` (raw IIQ WorkItem id). There is **no link
  to an individual request item**. All links are text with no foreign key, as in iiq_native.
- 17 business columns = the 17 response fields, same names and order. `comments` and `sign_off` are `jsonb`;
  `approval_item_count` / `approval_index` are `integer`; `approved` is boolean; there are 2 timestamps.
- `record_hash` = SHA-256 over **all 17** fields. Lineage: `IdentityIQ` / `native_iiq_java_api` /
  `sailpoint.object.WorkflowSummary$ApprovalSummary`, the read surface Part 1 names.
- Checked against real data: all 11 live approvals derive a key that exists in Part 1's `iiq_native` table, with equal
  `record_hash` and parent link (11/11).

## Policy (`kf_policy`)
Part 1 rules from `NativePolicyRepository`:
- `policyid` = `canonicalUuid(source_id)`, with fallback `deterministicUuid("native-policy|" + source_id)` (`+name` only when
  `source_id` is null). Upsert `ON CONFLICT (policyid)`.
- 21 response fields. 12 sit before `record_hash`; 9 (`state`, `violation_rule`, `violation_workflow`, `signature`,
  `certification_actions`, `violation_owner_type`, `violation_owner_rule_id`, `violation_owner_rule_name`, `is_template`)
  sit after `deleted_at`, as in iiq_native (`EntitySpec.trailing`).
- `descriptions` is the only `jsonb`. `violation_rule`, `violation_workflow`, `signature` and `certification_actions`
  are **text** in Part 1 (`certification_actions` is a comma-joined string). `constraint_count` is `integer` and
  `is_template` is boolean. The violation owner and owner rule stay raw text. A policy's constraints are their own
  entity (`policy-constraints`); only `constraint_count` is here.
- `record_hash` = SHA-256 over **all 21** fields. Lineage: `IdentityIQ` / `native_iiq_java_api` / `sailpoint.object.Policy`.
- Checked against real data: all 7 live policies produce the same `policyid` Part 1 stored (7/7). Their `record_hash`
  differs from the stored one, for a known reason. The rows in `iiq_native.sql` were written before Part 1's mapper filled
  `violation_owner_type`, `violation_owner_rule_id`, `violation_owner_rule_name` and `is_template` (added in Part 1 commit
  `b241b23`), so those 4 columns are NULL there. With those 4 set to NULL, Part 2's hash reproduces all 7 stored hashes
  exactly, so the hash rule is identical. Part 1 would produce Part 2's hash if it re-ran today.

## Provisioning-Item (`kf_provisioning_item`)
The AttributeRequest / PermissionRequest items **derived** from a provisioning transaction's plan. This is non-1:1 and
uses a dedicated `ProvisioningItemRepository`. Part 1 rules from `NativeProvisioningItemRepository` /
`NativeProvisioningTxnParser.toItem`:
- Items have **no IIQ id of their own**, and the key is **not** the transaction id. `provisioningitemid` =
  `deterministicUuid("native-provisioning-item|" + txn_source_id + "|" + item_type + "|" + operation + "|" + name + "|"
  + value_json + "|" + value + "|" + item_index)`, where `value_json` is the compact JSON text and a null part becomes `null`.
- **Parent link:** `txn_source_id` = the raw IIQ ProvisioningTransaction id. Part 1 fills it from the parent before the
  response is built. Part 2 also derives the two columns the response omits: `src_object_id` = `txn_source_id`, and
  `src_natural_key` = `txn_source_id|item_type|item_index`, a shorter formula than the PK.
- `source_object_type` is per row: `PERMISSION` items → `sailpoint.object.ProvisioningPlan$PermissionRequest`, all
  others → `sailpoint.object.ProvisioningPlan$AttributeRequest`.
- 17 response fields: `value_json` is the only `jsonb`; `value` / `permission_target` / `permission_rights` are text;
  `assignment` is boolean; `item_index` is integer.
- `record_hash` = Part 1's **16-field** subset: every field except `instance`. Like Part 1, rows are never deleted.
- Checked against real data: all 251 live items derive a key that exists in Part 1's `iiq_native` table, with equal
  `record_hash`, `source_object_type`, `src_object_id` and `src_natural_key` (251/251 each).

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
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_workgroup.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_role.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity_role.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity_entitlement.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_account_entitlement.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_role_entitlement.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_role_hierarchy.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity_request.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity_request_item.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_identity_request_approval.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_policy.sql
psql -h <host> -U <user> -d <db> -v schema=iiq_native_part2 -f schema/kf_provisioning_item.sql
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
java -jar target/kfagent-ingest.jar import-workgroup
java -jar target/kfagent-ingest.jar import-role
java -jar target/kfagent-ingest.jar import-identity-role
java -jar target/kfagent-ingest.jar import-identity-entitlement
java -jar target/kfagent-ingest.jar import-account-entitlement
java -jar target/kfagent-ingest.jar import-role-entitlement   # after import-entitlement
java -jar target/kfagent-ingest.jar import-role-hierarchy
java -jar target/kfagent-ingest.jar import-identity-request
java -jar target/kfagent-ingest.jar import-identity-request-item
java -jar target/kfagent-ingest.jar import-identity-request-approval
java -jar target/kfagent-ingest.jar import-policy
java -jar target/kfagent-ingest.jar import-provisioning-item
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
- `ProvisioningItemRepositoryTest` — derived key / src_natural_key / per-row object type / hash parity with Part 1 (incl.
  txn back-fill and null index), key is not the txn id, `instance` not hashed, DDL, binding.
- `PolicyTableTest` — key/hash parity with Part 1 incl. fallbacks, reproduces the stored hash of real "SOD Template" (with
  the 4 later-populated fields as stored), every field hashed, text-vs-jsonb fields, trailing DDL, binding.
- `IdentityRequestApprovalTableTest` — derived key/hash parity with Part 1 (incl. parent back-fill and null index), key
  is not the parent id, request + work-item links and no request-item link, jsonb comments/sign_off, DDL, binding.
- `IdentityRequestItemTableTest` — key/hash parity with Part 1 (incl. its parent back-fill), key is the item's own id
  and never the parent's, both fallbacks, every field hashed, integer `retries`, DDL, binding.
- `IdentityRequestTableTest` — reproduces the stored hash of real request 0000000008, no-fallback key (row fails like Part 1),
  every field hashed, integers / jsonb / nulls, DDL, binding.
- `RoleHierarchyRepositoryTest` — reproduces the real Part 1 edge from iiq_native.sql, natural key/id/hash parity,
  direction and type are part of the key and names are not, non-GUID fallbacks, DDL, binding.
- `RoleEntitlementRepositoryTest` — reproduces the real Part 1 row from iiq_native.sql, natural key/id/hash parity for
  CONTAINS_ALL / EQ / map-valued IN / permission / null-value rows, unique-match-only `entitlement_id` linking, Part 1
  null conventions, DDL with NOT NULLs, refusal without `kf_entitlement`.
- `AccountEntitlementTableTest` — key + `record_hash` parity with Part 1 for ATTRIBUTE / PERMISSION / TARGET_PERMISSION /
  null-type edges, type-dependent key formula, per-row object type (unknown type fails), 14-field hash, trailing DDL.
- `IdentityEntitlementTableTest` — key + `record_hash` parity with Part 1 (canonical id, composite natural-key fallback,
  non-GUID `source_id`), the 3 un-hashed fields, DDL, binding.
- `IdentityRoleTableTest` — composite edge key + `record_hash` parity with Part 1 (incl. null `assignment_id` and an
  all-null key), key is not the identity or role id, `identity_name` excluded from the hash, DDL, binding.
- `RoleTableTest` — `record_hash` + `roleid` parity with Part 1 (null and non-GUID fallbacks), all 33 fields
  hashed, DDL order incl. the 6 trailing columns, text-vs-jsonb fields, integer `risk_score_weight`, binding.
- `WorkgroupTableTest` — `record_hash` + `workgroupid` parity with Part 1 (null and non-GUID `source_id`
  fallbacks), every field hashed, DDL order = iiq_native.kf_workgroup, binding, Identity object type.
- `EntityImportServiceTest` — real HTTP against an in-process mock KF Agent: `start`/`limit` paging until a short
  page, per-row failure isolation + reporting, clear error on a non-array/500 response.
- `IdentityBindingTest` — response→column mapping via a recording JDBC stub: canonical `userid`, text/boolean,
  nested JSON → `jsonb`, ISO timestamps, SQL NULL for null/absent fields, metadata params. No live DB needed.

## Status / next
Identity, Entitlement, Application, Account, Workgroup, Role, Identity-Role, Identity-Entitlement, Account-Entitlement, Role-Entitlement, Role-Hierarchy, Identity-Request, Identity-Request-Item, Identity-Request-Approval, Policy and Provisioning-Item are done. Entitlement runs on the generic engine (`EntitySpec` + `EntityRepository` +
`EntityImportService`); further current-state entities are added as one `*Table` spec each. Identity is the complete first vertical slice (HTTP → parse → map → metadata → upsert → verification). The
column-spec + upsert engine is built to extend to the other ~39 endpoints, each with its own table, PK/salt,
`source_object_type`, `record_hash` behavior (where applicable), nested fields, and current-state vs
append-only mode. Known non-1:1 cases (e.g. workgroup-member native ids, the SQL-report endpoints) are mapped
individually, not forced into a universal mapping.

package com.keyforge.part2;

import com.keyforge.part2.config.AppConfig;
import com.keyforge.part2.event.EventDerivationService;
import com.keyforge.part2.event.EventLinkDerivationService;
import com.keyforge.part2.event.EventLinkRepository;
import com.keyforge.part2.event.EventRepository;
import com.keyforge.part2.config.ConfigException;
import com.keyforge.part2.db.PostgresConnection;
import com.keyforge.part2.http.KfAgentClient;
import com.keyforge.part2.http.FetchOptions;
import com.keyforge.part2.ingest.AccountEntitlementTable;
import com.keyforge.part2.ingest.AccountTable;
import com.keyforge.part2.ingest.AccessHistoryCertificationRepository;
import com.keyforge.part2.ingest.AccessHistoryEntitlementRepository;
import com.keyforge.part2.ingest.AccessHistoryIdentityEventRepository;
import com.keyforge.part2.ingest.AccessHistoryRoleEventRepository;
import com.keyforge.part2.ingest.AuditEventRepository;
import com.keyforge.part2.ingest.CertificationArchiveRepository;
import com.keyforge.part2.ingest.CertificationEntityTable;
import com.keyforge.part2.ingest.CertificationItemTable;
import com.keyforge.part2.ingest.CertificationTable;
import com.keyforge.part2.ingest.ApplicationTable;
import com.keyforge.part2.ingest.EntitlementAssignmentReport;
import com.keyforge.part2.ingest.EntitlementCertificationReport;
import com.keyforge.part2.ingest.EntitlementCertificationStatusReport;
import com.keyforge.part2.ingest.EntitlementTable;
import com.keyforge.part2.ingest.EntityImportService;
import com.keyforge.part2.ingest.EntityRepository;
import com.keyforge.part2.ingest.EntitySpec;
import com.keyforge.part2.ingest.ColumnSpec;
import com.keyforge.part2.ingest.IdentityTable;
import com.keyforge.part2.ingest.IdentityRequestApprovalTable;
import com.keyforge.part2.ingest.IdentityRequestItemTable;
import com.keyforge.part2.ingest.IdentityRequestTable;
import com.keyforge.part2.ingest.IdentityRepository;
import com.keyforge.part2.ingest.IdentityEntitlementTable;
import com.keyforge.part2.ingest.IdentityRoleTable;
import com.keyforge.part2.ingest.EntityWriter;
import com.keyforge.part2.ingest.GroupDefinitionTable;
import com.keyforge.part2.ingest.PolicyConstraintTable;
import com.keyforge.part2.ingest.PolicyTable;
import com.keyforge.part2.ingest.PolicyViolationTable;
import com.keyforge.part2.ingest.ProvisioningItemRepository;
import com.keyforge.part2.ingest.ProvisioningTxnTable;
import com.keyforge.part2.ingest.RoleEntitlementRepository;
import com.keyforge.part2.ingest.RoleHierarchyRepository;
import com.keyforge.part2.ingest.RoleTable;
import com.keyforge.part2.ingest.SoftDeleteSweeper;
import com.keyforge.part2.ingest.SqlReportImportService;
import com.keyforge.part2.ingest.SqlReportSpec;
import com.keyforge.part2.ingest.SqlReportRepository;
import com.keyforge.part2.ingest.SyslogEventImportService;
import com.keyforge.part2.ingest.SyslogEventRepository;
import com.keyforge.part2.ingest.TaskResultTable;
import com.keyforge.part2.ingest.TaskScheduleTable;
import com.keyforge.part2.ingest.WorkItemArchiveRepository;
import com.keyforge.part2.ingest.WorkItemTable;
import com.keyforge.part2.ingest.WorkgroupMemberRepository;
import com.keyforge.part2.ingest.WorkgroupMembersReport;
import com.keyforge.part2.ingest.WorkflowDefinitionRepository;
import com.keyforge.part2.ingest.WorkgroupTable;
import com.keyforge.part2.ingest.ImportResult;
import com.keyforge.part2.incremental.ExtractionMode;
import com.keyforge.part2.incremental.FamilyIncrementalImportService;
import com.keyforge.part2.incremental.IncrementalConfig;
import com.keyforge.part2.incremental.IncrementalCursor;
import com.keyforge.part2.incremental.IncrementalImportService;
import com.keyforge.part2.incremental.WatermarkService;
import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.ledger.RunContext;
import com.keyforge.part2.ledger.RunLedger;
import com.keyforge.part2.reconciliation.ReconciliationFindingRepository;
import com.keyforge.part2.reconciliation.WorkItemReconciliation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Part 2 CLI entry point. Commands:
 *
 * <pre>  import-identity      Fetch GET /kfagent/identities   and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_identity
 *  import-entitlement   Fetch GET /kfagent/entitlements and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_entitlement
 *  import-application   Fetch GET /kfagent/applications and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_application
 *  import-account       Fetch GET /kfagent/accounts     and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_account
 *  import-workgroup     Fetch GET /kfagent/workgroups   and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_workgroup
 *  import-role          Fetch GET /kfagent/roles        and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_role
 *  import-identity-role Fetch GET /kfagent/identity-roles and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_identity_role
 *  import-identity-entitlement
 *                       Fetch GET /kfagent/identity-entitlements and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_identity_entitlement
 *  import-account-entitlement
 *                       Fetch GET /kfagent/account-entitlements and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_account_entitlement
 *  import-role-entitlement
 *                       Fetch GET /kfagent/role-entitlements and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_role_entitlement
 *                       (needs kf_entitlement in the same schema: run import-entitlement first)
 *  import-role-hierarchy
 *                       Fetch GET /kfagent/role-hierarchy and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_role_hierarchy
 *  import-identity-request
 *                       Fetch GET /kfagent/identity-requests and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_identity_request
 *  import-identity-request-item
 *                       Fetch GET /kfagent/identity-request-items and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_identity_request_item
 *  import-identity-request-approval
 *                       Fetch GET /kfagent/identity-request-approvals and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_identity_request_approval
 *  import-policy        Fetch GET /kfagent/policies     and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_policy
 *  import-provisioning-item
 *                       Fetch GET /kfagent/provisioning-items and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_provisioning_item
 *  import-provisioning-transaction
 *                       Fetch GET /kfagent/provisioning-transactions and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_provisioning_txn
 *  import-work-item     Fetch GET /kfagent/work-items   and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_workitem
 *  import-workflow-definition
 *                       Fetch GET /kfagent/workflow-definitions and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_workflow_definition
 *  import-certification Fetch GET /kfagent/certifications and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_certification
 *  import-certification-entity
 *                       Fetch GET /kfagent/certification-entities and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_certification_entity
 *  import-certification-item
 *                       Fetch GET /kfagent/certification-items and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_certification_item
 *  import-certification-archive
 *                       Fetch GET /kfagent/certification-archives and append into &lt;PG_DEST_SCHEMA&gt;.kf_certification_archive
 *  import-policy-constraint
 *                       Fetch GET /kfagent/policy-constraints and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_policy_constraint
 *  import-policy-violation
 *                       Fetch GET /kfagent/policy-violations and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_violation
 *  import-entitlement-assignment
 *                       Fetch GET /kfagent/entitlement-assignment (SQL report) and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_entitlement_assignment
 *  import-workgroup-member
 *                       Fetch GET /kfagent/workgroupmember and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_workgroup_member
 *  import-entitlement-certification
 *                       Fetch GET /kfagent/entitlement-certification (SQL report) and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_entitlement_certification
 *  import-entitlement-certification-status
 *                       Fetch GET /kfagent/entitlement-certification-status (SQL report) and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_entitlement_certification_status
 *  import-workgroup-members
 *                       Fetch GET /kfagent/workgroup-members (SQL report) and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_workgroup_members
 *  import-audit-event
 *                       Fetch GET /kfagent/audit-events and append into &lt;PG_DEST_SCHEMA&gt;.kf_audit_event
 *  import-task-result
 *                       Fetch GET /kfagent/task-results and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_task_result
 *  import-task-schedule
 *                       Fetch GET /kfagent/task-schedules and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_task_schedule
 *  import-workitem-archive
 *                       Fetch GET /kfagent/workitem-archives and append into &lt;PG_DEST_SCHEMA&gt;.kf_workitem_archive
 *  import-group-definition
 *                       Fetch GET /kfagent/group-definitions and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_group_definition
 *  import-access-history
 *                       Fetch GET /kfagent/access-history and append into &lt;PG_DEST_SCHEMA&gt;.kf_access_hist_entitlement
 *  import-access-history-identity-event
 *                       Fetch GET /kfagent/access-history/identity-events and append into &lt;PG_DEST_SCHEMA&gt;.kf_access_hist_identity_event
 *  import-access-history-certification
 *                       Fetch GET /kfagent/access-history/certifications and append into &lt;PG_DEST_SCHEMA&gt;.kf_access_hist_certification
 *  import-access-history-role-event
 *                       Fetch GET /kfagent/access-history/role-events and append into &lt;PG_DEST_SCHEMA&gt;.kf_access_hist_role_event
 *  import-syslog-event
 *                       Page GET /kfagent/syslog-events and append into &lt;PG_DEST_SCHEMA&gt;.kf_syslog_event</pre>
 *
 * Configuration is entirely external (environment variables / -D system properties) — see {@link AppConfig}.
 * Exit codes: 0 ok, 2 usage, 3 config error, 4 HTTP/source error, 5 PostgreSQL error, 6 completed with row
 * failures, 7 scan not confirmed complete (import-syslog-event, only when a page reports a source count), 1 unexpected.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String command = args.length == 0 ? "" : args[0].trim();
        if (START_REST.equals(command)) {
            int code = RestServer.startFromEnvironment();
            if (code != 0) {
                System.exit(code);
            }
            return; // the server's threads keep the JVM running until it is stopped
        }
        ExtractionMode mode = ExtractionMode.fromArgs(java.util.Arrays.copyOfRange(args, Math.min(1, args.length),
                args.length));
        String ledgerCommand = mode.isIncremental() ? command + " --incremental" : command;
        System.exit(RunLedger.run(ledgerCommand, () -> dispatch(command, mode)));
    }

    /** Starts the HTTP trigger for the commands below ({@link RestServer}); not itself an import. */
    static final String START_REST = "start-rest";

    /** Runs one full-mode command (see {@link #dispatch(String, ExtractionMode)}). */
    static int dispatch(String command) {
        return dispatch(command, ExtractionMode.FULL);
    }

    /**
     * Runs one command and returns its exit code; every import is recorded in kf_extraction_run. Incremental mode is
     * opt-in ({@code --incremental}) and only for the entities whose KF Agent endpoint supports {@code modifiedAfter}.
     */
    static int dispatch(String command, ExtractionMode mode) {
        return dispatch(command, mode, FetchOptions.none());
    }

    /**
     * Runs one command and returns its exit code; every import is recorded in kf_extraction_run. Incremental mode is
     * opt-in ({@code --incremental}) and only for the entities whose KF Agent endpoint supports {@code modifiedAfter}.
     */
    static int dispatch(String command, ExtractionMode mode, FetchOptions options) {
        if (options == null) options = FetchOptions.none();
        if (!options.isEmpty() && (mode.isIncremental() || !supportsCallerFetchOptions(command))) {
            RunLedger.discard();
            return 2;
        }
        if (!options.isEmpty()) {
            RunLedger.request(requestJson(options));
        }
        if (mode.isIncremental()) {
            Function<String, EntityWriter> writer = INCREMENTAL_WRITERS.get(command);
            if (writer != null) {
                // "changed": modified after the bound, or never modified and created after it (IIQ leaves modified
                // NULL until an object is first updated); the watermark is max(coalesce(modified_at, created_at))
                return runIncremental(writer, IncrementalCursor.CHANGED);
            }
            IncrementalFamily family = INCREMENTAL_FAMILIES.get(command);
            if (family != null) {
                return runFamilyIncremental(family);
            }
            String parentCommand = INCREMENTAL_CHILD_OF.get(command);
            if (parentCommand != null) {
                RunLedger.discard(); // a usage error is not an import run
                System.err.println("'" + command + "' has no change time of its own; it is imported incrementally "
                        + "together with its parent: run '" + parentCommand + " --incremental'. Without "
                        + "--incremental, '" + command + "' runs as a full import.");
                return 2;
            }
            Function<String, EntityWriter> changedWriter = CHANGED_INCREMENTAL_WRITERS.get(command);
            if (changedWriter != null) {
                return runIncremental(changedWriter, IncrementalCursor.CHANGED);
            }
            Function<String, EntityWriter> eventWriter = EVENT_INCREMENTAL_WRITERS.get(command);
            if (eventWriter != null) {
                return runIncremental(eventWriter, IncrementalCursor.CREATED);
            }
            if (SYSLOG_COMMAND.equals(command)) {
                return runSyslogIncremental();
            }
            if ("derive-native-events".equals(command)) {
                return runDeriveNativeEvents(FetchOptions.none(), true);
            }
            if ("derive-native-event-links".equals(command)) {
                return runDeriveNativeEventLinks(FetchOptions.none(), true);
            }
            RunLedger.discard(); // a usage error is not an import run
            System.err.println("--incremental is supported only for " + INCREMENTAL_WRITERS.keySet() + ", "
                    + INCREMENTAL_FAMILIES.keySet() + ", " + CHANGED_INCREMENTAL_WRITERS.keySet() + ", "
                    + EVENT_INCREMENTAL_WRITERS.keySet() + " and " + SYSLOG_COMMAND
                    + " (their KF Agent endpoints accept modifiedAfter); '" + command + "' runs in full mode only.");
            return 2;
        }
        return switch (command) {
            case "import-identity" -> runImport(Main::identityWriter, options);
            case "import-entitlement" -> runImport(EntitlementTable.SPEC, options);
            case "import-application" -> runImport(ApplicationTable.SPEC, options);
            case "import-account" -> runImport(AccountTable.SPEC, options);
            case "import-workgroup" -> runImport(WorkgroupTable.SPEC, options);
            case "import-role" -> runImport(RoleTable.SPEC, options);
            case "import-identity-role" -> runImport(IdentityRoleTable.SPEC, options);
            case "import-identity-entitlement" -> runImport(IdentityEntitlementTable.SPEC, options);
            case "import-account-entitlement" -> runImport(AccountEntitlementTable.SPEC, options);
            case "import-role-entitlement" -> runImport(RoleEntitlementRepository::new, options);
            case "import-role-hierarchy" -> runImport(RoleHierarchyRepository::new, options);
            case "import-identity-request" -> runImport(IdentityRequestTable.SPEC, options);
            case "import-identity-request-item" -> runImport(IdentityRequestItemTable.SPEC, options);
            case "import-identity-request-approval" -> runImport(IdentityRequestApprovalTable.SPEC, options);
            case "import-policy" -> runImport(PolicyTable.SPEC, options);
            case "import-provisioning-item" -> runImport(ProvisioningItemRepository::new, options);
            case "import-provisioning-transaction" -> runImport(ProvisioningTxnTable.SPEC, options);
            case "import-work-item" -> runImport(WorkItemTable.SPEC, options);
            case "import-workflow-definition" -> runImport(WorkflowDefinitionRepository::new, options);
            case "import-certification" -> runImport(CertificationTable.SPEC, options);
            case "import-certification-entity" -> runImport(CertificationEntityTable.SPEC, options);
            case "import-certification-item" -> runImport(CertificationItemTable.SPEC, options);
            case "import-certification-archive" -> runImport(CertificationArchiveRepository::new, options);
            case "import-policy-constraint" -> runImport(PolicyConstraintTable.SPEC, options);
            case "import-policy-violation" -> runImport(PolicyViolationTable.SPEC, options);
            case "import-entitlement-assignment" -> runSqlReport(EntitlementAssignmentReport.SPEC, options);
            case "import-workgroup-member" -> runImport(WorkgroupMemberRepository::new, options);
            case "import-entitlement-certification" -> runSqlReport(EntitlementCertificationReport.SPEC, options);
            case "import-entitlement-certification-status" ->
                    runSqlReport(EntitlementCertificationStatusReport.SPEC, options);
            case "import-workgroup-members" -> runSqlReport(WorkgroupMembersReport.SPEC, options);
            case "import-audit-event" -> runImport(AuditEventRepository::new, options);
            case "import-task-result" -> runImport(TaskResultTable.SPEC, options);
            case "import-task-schedule" -> runImport(TaskScheduleTable.SPEC, options);
            case "import-workitem-archive" -> runImport(WorkItemArchiveRepository::new, options);
            case "import-group-definition" -> runImport(GroupDefinitionTable.SPEC, options);
            case "import-access-history" -> runImport(AccessHistoryEntitlementRepository::new, options);
            case "import-access-history-identity-event" ->
                    runImport(AccessHistoryIdentityEventRepository::new, options);
            case "import-access-history-certification" ->
                    runImport(AccessHistoryCertificationRepository::new, options);
            case "import-access-history-role-event" ->
                    runImport(AccessHistoryRoleEventRepository::new, options);
            case "import-syslog-event" -> options.isEmpty() ? runSyslogImport()
                    : runImport(SyslogEventRepository::new, options);
            case "reconcile-native-workitems" -> runReconcileWorkItems();
            case "derive-native-events" -> runDeriveNativeEvents(options, false);
            case "derive-native-event-links" -> runDeriveNativeEventLinks(options, false);
            default -> {
                RunLedger.discard(); // a usage error is not an import run
                printUsage();
                yield 2;
            }
        };
    }

    /** The caller's fetch scope as recorded in the run ledger's {@code request} column. */
    static String requestJson(FetchOptions options) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("start", options.start());
        m.put("limit", options.limit());
        m.put("modifiedAfter", options.modifiedAfter());
        m.put("filters", options.filters());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    static boolean supportsCallerFetchOptions(String command) {
        return command != null && (fetchResource(command) != null || DERIVE_COMMANDS.contains(command));
    }

    /** The commands that build Part 2's own event / link tables; they accept start/limit and incremental mode. */
    static final Set<String> DERIVE_COMMANDS = Set.of("derive-native-events", "derive-native-event-links");

    /**
     * The database's current time. The derived tables' watermark is compared with {@code extracted_at}, which the
     * database stamps, so it must come from the same clock (Part 2's host clock may differ from the database server's).
     */
    static Instant databaseNow(Connection conn) throws SQLException {
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT now()")) {
            rs.next();
            return rs.getObject(1, java.time.OffsetDateTime.class).toInstant();
        }
    }

    /** Watermark keys of the derived tables: when the last successful pass started (the database's clock). */
    static final String EVENTS_WATERMARK = "derived:kf_event";
    static final String LINKS_WATERMARK = "derived:kf_event_link";

    /**
     * The derivation scope: the caller's start/limit window of each source table, and in incremental mode only the
     * source rows Part 2 wrote since the stored watermark (none stored = everything, the baseline).
     */
    static EventDerivationService.Scope deriveScope(FetchOptions options, Instant changedSince) {
        return new EventDerivationService.Scope(options.start(), options.limit(), changedSince);
    }

    /** Part 1 applies exact AND filters to response scalar fields; JSON/technical fields are excluded. */
    static Set<String> fetchFilterFields(String command) {
        if ("import-identity".equals(command)) {
            return IdentityTable.BUSINESS.stream().filter(c -> c.kind != ColumnSpec.Kind.JSONB
                    && c.kind != ColumnSpec.Kind.JSON_TEXT).map(c -> c.name).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        EntitySpec spec = fetchSpec(command);
        if (spec == null) return switch (command) {
            case "import-role-entitlement" -> Set.of("source_role_id", "role_name", "entitlement_type", "application_id",
                    "application_name", "profile_ordinal", "constraint_path", "filter_expression", "filter_operation",
                    "attribute_name", "attribute_value", "permission_target", "permission_rights", "permission_annotation");
            case "import-role-hierarchy" -> Set.of("source_role_id", "role_name", "related_role_source_id",
                    "related_role_name", "relationship_type");
            case "import-provisioning-item" -> Set.of("txn_source_id", "identity_name", "item_type", "operation",
                    "application_name", "native_identity", "instance", "account_operation", "name", "value",
                    "assignment_id", "assignment", "permission_target", "permission_rights", "request_id", "item_index");
            case "import-workflow-definition" -> Set.of("source_id", "name", "type", "handler", "description",
                    "task_type", "created_at", "modified_at");
            case "import-certification-archive" -> Set.of("source_id", "name", "certification_id",
                    "certification_group_id", "creator_name", "owner_name", "comments", "signed", "expiration",
                    "created_at", "modified_at", "archived_ts");
            case "import-workgroup-member" -> Set.of("workgroup_id", "workgroup_name", "identity_id", "member_name",
                    "first_name", "last_name", "member_is_workgroup");
            case "import-audit-event" -> Set.of("source_id", "action", "audit_source", "target", "application",
                    "account_name", "instance", "attribute_name", "attribute_value", "interface_name", "server_host",
                    "client_host", "tracking_id", "string1", "string2", "string3", "string4", "created_at");
            case "import-workitem-archive" -> Set.of("source_id", "work_item_id", "name", "type", "state", "level",
                    "requester", "assignee", "owner_name", "completer", "completion_comments", "is_signed", "target_class",
                    "target_id", "target_name", "identity_request_id", "certification_id", "certification_entity_id",
                    "certification_item_id", "entity_type", "created_at", "modified_at", "expiration_ts", "archived_ts");
            case "import-access-history" -> Set.of("source_id", "name", "entity_id", "entity_name", "identity_id",
                    "identity_name", "identity_entitlement_id", "application_id", "application_name", "native_identity",
                    "instance", "display_value", "attribute_name", "attribute_value", "type", "granted_by_role", "role_id",
                    "request_item_id", "pending_request_item_id", "certification_item_id", "deleted", "effective_date",
                    "extended_to_date", "latest", "compressed", "brief", "full_capture", "patch", "smart_hash", "full_hash",
                    "json_format", "transform_type", "patch_doc_parent", "created_at", "modified_at");
            case "import-access-history-certification" -> Set.of("source_id", "name", "cert_id", "cert_type", "cert_name",
                    "cert_display_name", "finished", "signed", "created_at", "modified_at");
            case "import-access-history-identity-event", "import-access-history-role-event" -> Set.of(
                    "source_id", "name", "entity_id", "entity_name", "defined_entity_name", "event_type", "event_category",
                    "event_source_type", "event_date", "prev_capture_id", "capture_id", "audit_event_id", "property_name",
                    "old_value", "new_value", "account_id", "acct_app_id", "acct_app_name", "acct_app_instance",
                    "acct_display_name", "acct_native_id", "pending_request_item_id", "request_item_id", "identity_request_id",
                    "identity_entitlement_id", "created_at", "modified_at", "qry_property1", "qry_property2", "qry_property3",
                    "qry_property4", "qry_property5", "qry_property6", "qry_property7", "qry_property8", "qry_property9", "qry_property10");
            case "import-syslog-event" -> Set.of("source_id", "quick_key", "event_level", "server", "username", "thread",
                    "classname", "line_number", "message", "stacktrace", "created_at");
            // Part 1 filters a report on any of its SELECT columns (exact match)
            case "import-entitlement-assignment" -> Set.copyOf(EntitlementAssignmentReport.COLUMNS);
            case "import-entitlement-certification" -> Set.copyOf(EntitlementCertificationReport.COLUMNS);
            case "import-entitlement-certification-status" -> Set.copyOf(EntitlementCertificationStatusReport.COLUMNS);
            case "import-workgroup-members" -> Set.copyOf(WorkgroupMembersReport.COLUMNS);
            default -> Set.of();
        };
        return spec.allBusiness().stream().filter(c -> c.kind != ColumnSpec.Kind.JSONB
                && c.kind != ColumnSpec.Kind.JSON_TEXT).map(c -> c.name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static boolean supportsCallerModifiedAfter(String command) {
        return INCREMENTAL_WRITERS.containsKey(command) || INCREMENTAL_FAMILIES.containsKey(command)
                || INCREMENTAL_CHILD_OF.containsKey(command) || CHANGED_INCREMENTAL_WRITERS.containsKey(command)
                || EVENT_INCREMENTAL_WRITERS.containsKey(command) || SYSLOG_COMMAND.equals(command)
                || SQL_REPORT_COMMANDS.contains(command); // Part 1 binds modifiedAfter inside the report SQL
    }

    /** The four SQL-report commands. */
    static final Set<String> SQL_REPORT_COMMANDS = Set.of("import-entitlement-assignment",
            "import-entitlement-certification", "import-entitlement-certification-status", "import-workgroup-members");

    static String fetchResource(String command) {
        EntitySpec spec = fetchSpec(command);
        if (spec != null) return spec.resource();
        return switch (command) {
            case "import-identity" -> "identities";
            case "import-role-entitlement" -> RoleEntitlementRepository.RESOURCE;
            case "import-role-hierarchy" -> RoleHierarchyRepository.RESOURCE;
            case "import-provisioning-item" -> ProvisioningItemRepository.RESOURCE;
            case "import-workflow-definition" -> WorkflowDefinitionRepository.RESOURCE;
            case "import-certification-archive" -> CertificationArchiveRepository.RESOURCE;
            case "import-workgroup-member" -> WorkgroupMemberRepository.RESOURCE;
            case "import-audit-event" -> AuditEventRepository.RESOURCE;
            case "import-workitem-archive" -> WorkItemArchiveRepository.RESOURCE;
            case "import-access-history" -> AccessHistoryEntitlementRepository.RESOURCE;
            case "import-access-history-identity-event" -> AccessHistoryIdentityEventRepository.RESOURCE;
            case "import-access-history-certification" -> AccessHistoryCertificationRepository.RESOURCE;
            case "import-access-history-role-event" -> AccessHistoryRoleEventRepository.RESOURCE;
            case "import-syslog-event" -> SyslogEventRepository.RESOURCE;
            case "import-entitlement-assignment" -> EntitlementAssignmentReport.RESOURCE;
            case "import-entitlement-certification" -> EntitlementCertificationReport.RESOURCE;
            case "import-entitlement-certification-status" -> EntitlementCertificationStatusReport.RESOURCE;
            case "import-workgroup-members" -> WorkgroupMembersReport.RESOURCE;
            default -> null;
        };
    }

    private static EntitySpec fetchSpec(String command) {
        return switch (command) {
            case "import-entitlement" -> EntitlementTable.SPEC;
            case "import-application" -> ApplicationTable.SPEC;
            case "import-account" -> AccountTable.SPEC;
            case "import-workgroup" -> WorkgroupTable.SPEC;
            case "import-role" -> RoleTable.SPEC;
            case "import-identity-role" -> IdentityRoleTable.SPEC;
            case "import-identity-entitlement" -> IdentityEntitlementTable.SPEC;
            case "import-account-entitlement" -> AccountEntitlementTable.SPEC;
            case "import-identity-request" -> IdentityRequestTable.SPEC;
            case "import-identity-request-item" -> IdentityRequestItemTable.SPEC;
            case "import-identity-request-approval" -> IdentityRequestApprovalTable.SPEC;
            case "import-policy" -> PolicyTable.SPEC;
            case "import-provisioning-transaction" -> ProvisioningTxnTable.SPEC;
            case "import-work-item" -> WorkItemTable.SPEC;
            case "import-certification" -> CertificationTable.SPEC;
            case "import-certification-entity" -> CertificationEntityTable.SPEC;
            case "import-certification-item" -> CertificationItemTable.SPEC;
            case "import-policy-constraint" -> PolicyConstraintTable.SPEC;
            case "import-policy-violation" -> PolicyViolationTable.SPEC;
            case "import-task-result" -> TaskResultTable.SPEC;
            case "import-task-schedule" -> TaskScheduleTable.SPEC;
            case "import-group-definition" -> GroupDefinitionTable.SPEC;
            default -> null;
        };
    }

    /** The commands with opt-in incremental mode, and the writer each uses (the same one as its full import). */
    static final Map<String, Function<String, EntityWriter>> INCREMENTAL_WRITERS = Map.of(
            "import-identity", Main::identityWriter,
            "import-entitlement", schema -> new EntityRepository(schema, EntitlementTable.SPEC),
            "import-application", schema -> new EntityRepository(schema, ApplicationTable.SPEC),
            "import-account", schema -> new EntityRepository(schema, AccountTable.SPEC),
            "import-role", schema -> new EntityRepository(schema, RoleTable.SPEC));

    /**
     * The append-only, event-shaped commands with opt-in incremental mode (each uses the same writer as its full
     * import). Their KF Agent {@code modifiedAfter} bounds IIQ {@code created}; the watermark is
     * {@code max(created_at)}. Rows are deduplicated on append, so the overlap window never duplicates them.
     */
    static final Map<String, Function<String, EntityWriter>> EVENT_INCREMENTAL_WRITERS = Map.of(
            "import-audit-event", AuditEventRepository::new,
            "import-workitem-archive", WorkItemArchiveRepository::new,
            "import-certification-archive", CertificationArchiveRepository::new,
            "import-access-history", AccessHistoryEntitlementRepository::new,
            "import-access-history-identity-event", AccessHistoryIdentityEventRepository::new,
            "import-access-history-certification", AccessHistoryCertificationRepository::new,
            "import-access-history-role-event", AccessHistoryRoleEventRepository::new);

    /**
     * Current-state commands with opt-in incremental mode whose IIQ objects may keep a NULL {@code modified} until
     * first updated (each uses the same writer as its full import). The KF Agent's {@code modifiedAfter} returns
     * objects modified after the bound or never modified and created after it; the watermark is
     * {@code max(coalesce(modified_at, created_at))}. Incremental runs never sweep (the periodic full import does).
     */
    static final Map<String, Function<String, EntityWriter>> CHANGED_INCREMENTAL_WRITERS = Map.of(
            "import-work-item", schema -> new EntityRepository(schema, WorkItemTable.SPEC),
            "import-task-result", schema -> new EntityRepository(schema, TaskResultTable.SPEC),
            "import-certification-item", schema -> new EntityRepository(schema, CertificationItemTable.SPEC),
            "import-certification-entity", schema -> new EntityRepository(schema, CertificationEntityTable.SPEC),
            "import-identity-entitlement", schema -> new EntityRepository(schema, IdentityEntitlementTable.SPEC));

    /** A parent entity plus the child entities IIQ builds from it, imported incrementally under one watermark. */
    record IncrementalFamily(Function<String, EntityWriter> parent, List<Function<String, EntityWriter>> children) { }

    /**
     * Parents whose incremental run also refreshes their children (same writers as the full imports), under ONE shared
     * watermark that advances only if the parent and every child loaded without a failure.
     */
    static final Map<String, IncrementalFamily> INCREMENTAL_FAMILIES = Map.of(
            "import-identity-request", new IncrementalFamily(
                    schema -> new EntityRepository(schema, IdentityRequestTable.SPEC),
                    List.of(schema -> new EntityRepository(schema, IdentityRequestItemTable.SPEC),
                            schema -> new EntityRepository(schema, IdentityRequestApprovalTable.SPEC))),
            "import-provisioning-transaction", new IncrementalFamily(
                    schema -> new EntityRepository(schema, ProvisioningTxnTable.SPEC),
                    List.of(ProvisioningItemRepository::new)),
            // account-entitlement edges have no change time of their own: they are bounded by their account's (Link's)
            // change time, so this command also refreshes the changed accounts (same writer as import-account) under
            // its own family watermark, independent of import-account's.
            "import-account-entitlement", new IncrementalFamily(
                    schema -> new EntityRepository(schema, AccountTable.SPEC),
                    List.of(schema -> new EntityRepository(schema, AccountEntitlementTable.SPEC))));

    /** Child commands and the parent command that imports them incrementally. */
    static final Map<String, String> INCREMENTAL_CHILD_OF = Map.of(
            "import-identity-request-item", "import-identity-request",
            "import-identity-request-approval", "import-identity-request",
            "import-provisioning-item", "import-provisioning-transaction");

    /** Syslog keeps its own paged, complete-scan-guarded import; its incremental mode reuses that scan. */
    static final String SYSLOG_COMMAND = "import-syslog-event";

    /**
     * The Identity table's dedicated repository as an {@link EntityWriter}: PK {@code userid} =
     * {@link com.keyforge.part2.id.CanonicalIds#identityUserid}; unchanged identities are skipped by their record hash.
     */
    public static EntityWriter identityWriter(String schema) {
        IdentityRepository repository = new IdentityRepository(schema);
        return new EntityWriter() {
            @Override public String entity() { return "Identity"; }
            @Override public String resource() { return "identities"; }
            @Override public String targetTable() { return repository.targetTable(); }
            @Override public void ensureSchemaAndTable(Connection conn) throws SQLException {
                repository.ensureSchemaAndTable(conn);
            }
            @Override public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String runId)
                    throws SQLException {
                return switch (repository.upsert(conn, row, runId)) {
                    case INSERTED -> EntityRepository.Outcome.INSERTED;
                    case UPDATED -> EntityRepository.Outcome.UPDATED;
                    case SKIPPED -> EntityRepository.Outcome.SKIPPED;
                };
            }
            @Override public String ref(JsonNode row) {
                return "identity " + row.path("name").asText(null) + " (" + row.path("source_id").asText(null) + ")";
            }
            @Override public String key(JsonNode row) {
                return IdentityRepository.userid(row);
            }
            @Override public String keyColumn() {
                return IdentityTable.PK;
            }
        };
    }

    /** One {@code modifiedAfter}-bounded {@code start}/{@code limit} page of a KF Agent resource. */
    static IncrementalImportService.Fetcher pagedFetcher(KfAgentClient client) {
        return (resource, start, limit, modifiedAfterIso) -> client.fetch(resource, start, limit, modifiedAfterIso).body();
    }

    /**
     * Incremental import (opt-in): rows modified after the stored watermark minus the overlap, read page by page, the
     * ordinary writer, and a watermark advance only when no row failed. Never sweeps.
     */
    private static int runIncremental(Function<String, EntityWriter> writerForSchema, IncrementalCursor cursor) {
        try {
            AppConfig config = AppConfig.load();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            EntityWriter writer = writerForSchema.apply(config.pgSchema());
            Duration overlap = IncrementalConfig.overlapWindow();
            IncrementalImportService service = new IncrementalImportService(pagedFetcher(client), writer,
                    new WatermarkService(config.pgSchema()), overlap, cursor, config.pageSize(),
                    java.time.Clock.systemUTC());

            try (Connection conn = PostgresConnection.open(config)) {
                IncrementalImportService.Outcome o = service.run(conn, runId);
                RunLedger.window(o.bound(), o.fetchedAt());
                RunLedger.record(counts(writer.targetTable(), o.result(), null));
                System.out.println();
                System.out.println(o.result().summary());
                System.out.println("  mode:      incremental (overlap " + overlap.toMinutes() + " min); watermark "
                        + WatermarkService.watermarkKey(writer.targetTable()) + " was "
                        + (o.stored() == null ? "unset -> full fetch (baseline)" : o.stored())
                        + "; modifiedAfter=" + (o.bound() == null ? "none" : o.bound())
                        + " (IIQ " + cursor.sourceField() + ")");
                System.out.println("  watermark: " + (o.advanced() ? "advanced to " + o.newWatermark()
                        : "NOT advanced (" + (o.result().failed() > 0 ? o.result().failed()
                        + " row failure(s); the next run re-reads from the unchanged watermark)"
                        : "no newer change time than the stored watermark") + ")"));
                System.out.println("  sweep:     not run (an incremental import never marks rows deleted)");
                if (o.result().failed() > 0) {
                    RunLedger.error("watermark not advanced: " + o.result().failed() + " row failure(s)");
                }
                return o.result().failed() > 0 ? 6 : 0;
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            RunLedger.error("configuration error: " + e.getMessage());
            return 3;
        } catch (KfAgentClient.KfAgentHttpException e) {
            System.err.println("KF Agent HTTP/source error: " + e.getMessage());
            RunLedger.error("KF Agent HTTP/source error: " + e.getMessage());
            return 4;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            RunLedger.error("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            RunLedger.error("unexpected error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Reconciliation (Part 1 reconcile-native-workitems-db): reads the loaded approval / work-item / work-item-archive
     * tables and persists one finding for this run to kf_reconciliation_finding. Read-only over every other table.
     */
    private static int runReconcileWorkItems() {
        try {
            AppConfig config = AppConfig.loadDatabaseOnly(); // reads loaded tables only; no KF Agent call
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);
            System.out.println("WorkItem reconciliation (read-only over loaded tables; writes only "
                    + "kf_reconciliation_finding): approval work_item_id references vs kf_workitem + kf_workitem_archive");
            try (Connection conn = PostgresConnection.open(config)) {
                WorkItemReconciliation svc = new WorkItemReconciliation(config.pgSchema());
                WorkItemReconciliation.Result r = svc.reconcile(conn, runId);
                RunLedger.record(new RunContext.EntityCount(ReconciliationFindingRepository.TABLE, 1, 1, 0, 0, 0,
                        null, null, null));
                System.out.println();
                if (r.skipped) {
                    System.out.println("Reconciliation SKIPPED: " + r.skipReason);
                } else {
                    System.out.println("Live WorkItems (kf_workitem):                " + r.liveWorkItems);
                    System.out.println("WorkItem archives (kf_workitem_archive):     " + r.workItemArchives);
                    System.out.println("Approval work_item_id references (distinct): " + r.approvalRefs);
                    System.out.println("  matched to a live WorkItem:      " + r.matchedLive);
                    System.out.println("  matched to an archived WorkItem: " + r.matchedArchive);
                    System.out.println("  matched to either:               " + r.matchedEither);
                    System.out.println("  UNMATCHED:                       " + r.unmatched);
                    if (!r.unmatchedSamples.isEmpty()) {
                        System.out.println("  unmatched sample ids: " + r.unmatchedSamples);
                    }
                }
                System.out.println();
                System.out.println("Determination: " + r.determination);
                System.out.println("Finding " + r.findingId + " persisted to " + svc.findings().targetTable()
                        + " (check '" + WorkItemReconciliation.CHECK_NAME + "', run_id " + runId + ")");
                return 0;
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            RunLedger.error("configuration error: " + e.getMessage());
            return 3;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            RunLedger.error("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            RunLedger.error("unexpected error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Event derivation (Part 1 derive-native-events-db, events only): reads the loaded approval / task-result /
     * provisioning-transaction / work-item-archive / certification-item tables and appends the derived events to
     * kf_event in one transaction. Needs PostgreSQL only (no KF Agent call, KFAGENT_BASE_URL not required).
     */
    private static int runDeriveNativeEvents(FetchOptions options, boolean incremental) {
        try {
            AppConfig config = AppConfig.loadDatabaseOnly();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);
            System.out.println("Native event derivation (append-only; reads loaded tables only, no KF Agent call): "
                    + "kf_identity_request_approval, kf_task_result, kf_provisioning_txn, kf_workitem_archive, "
                    + "kf_certification_item -> kf_event");
            try (Connection conn = PostgresConnection.open(config)) {
                WatermarkService watermarks = new WatermarkService(config.pgSchema());
                Instant since = incremental ? watermarks.readKey(conn, EVENTS_WATERMARK) : null;
                Instant runStart = databaseNow(conn); // the clock that stamps extracted_at
                if (incremental) {
                    RunLedger.window(since, runStart);
                }
                EventDerivationService svc = new EventDerivationService(config.pgSchema());
                EventDerivationService.Result r = svc.derive(conn, runId, deriveScope(options, since));
                if (incremental) {
                    conn.setAutoCommit(true);
                    watermarks.advanceKeyIfSuccessful(conn, EVENTS_WATERMARK, runStart, 0, runId,
                            IncrementalCursor.EXTRACTED);
                    System.out.println("  mode:      incremental: source rows written since "
                            + (since == null ? "the beginning (baseline)" : since) + "; watermark now " + runStart);
                }
                RunLedger.record(new RunContext.EntityCount(EventRepository.TABLE, r.eventsDerived, r.eventsInserted,
                        0, 0, r.eventsAlreadyPresent(), null, null, null));
                System.out.println();
                System.out.println("Source rows read: approval=" + r.approvalRows + ", task_result=" + r.taskRows
                        + ", provisioning_txn=" + r.provRows + ", workitem_archive=" + r.archiveRows
                        + ", certification_item=" + r.certItemRows);
                if (!r.sourcesMissing.isEmpty()) {
                    System.out.println("  source tables absent (no events derived from them): " + r.sourcesMissing);
                }
                System.out.println("Events derived: " + r.eventsDerived + " (inserted " + r.eventsInserted
                        + ", already present " + r.eventsAlreadyPresent() + ") -> " + svc.eventTable());
                for (Map.Entry<String, Integer> e : r.eventsByType.entrySet()) {
                    System.out.println("    " + e.getKey() + ": " + e.getValue() + " (inserted "
                            + r.insertedByType.getOrDefault(e.getKey(), 0) + ")");
                }
                return 0;
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            RunLedger.error("configuration error: " + e.getMessage());
            return 3;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error (the failing chunk was rolled back; chunks already committed stay, "
                    + "and a re-run completes the rest): " + e.getMessage());
            RunLedger.error("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            RunLedger.error("unexpected error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Event-link derivation (Part 1 derive-native-events-db, link half): re-derives each loaded source row's event id
     * and its explicit links, and appends to kf_event_link only the links whose event is already in kf_event, in one
     * transaction. Reads kf_event, never writes it. PostgreSQL only (no KF Agent call, KFAGENT_BASE_URL not required).
     */
    private static int runDeriveNativeEventLinks(FetchOptions options, boolean incremental) {
        try {
            AppConfig config = AppConfig.loadDatabaseOnly();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);
            System.out.println("Native event-link derivation (append-only, EXPLICIT ids only; reads loaded tables and "
                    + "kf_event, no KF Agent call) -> kf_event_link");
            try (Connection conn = PostgresConnection.open(config)) {
                WatermarkService watermarks = new WatermarkService(config.pgSchema());
                Instant since = incremental ? watermarks.readKey(conn, LINKS_WATERMARK) : null;
                Instant runStart = databaseNow(conn); // the clock that stamps extracted_at
                if (incremental) {
                    RunLedger.window(since, runStart);
                }
                EventLinkDerivationService svc = new EventLinkDerivationService(config.pgSchema());
                EventLinkDerivationService.Result r = svc.derive(conn, runId, deriveScope(options, since));
                if (incremental) {
                    // a link whose event is not stored yet must be retried, so the watermark only moves when none is
                    conn.setAutoCommit(true);
                    boolean advanced = watermarks.advanceKeyIfSuccessful(conn, LINKS_WATERMARK, runStart,
                            r.linksWithoutEvent, runId, IncrementalCursor.EXTRACTED);
                    System.out.println("  mode:      incremental: source rows written since "
                            + (since == null ? "the beginning (baseline)" : since) + "; watermark "
                            + (advanced ? "now " + runStart : "NOT advanced (" + r.linksWithoutEvent
                            + " link(s) wait for their event; the next run reads the same rows)"));
                }
                RunLedger.record(new RunContext.EntityCount(EventLinkRepository.TABLE, r.linksDerived,
                        r.linksInserted, 0, 0, r.linksAlreadyPresent + r.linksWithoutEvent, null, null, null));
                System.out.println();
                System.out.println("Source rows read: approval=" + r.approvalRows + ", task_result=" + r.taskRows
                        + ", provisioning_txn=" + r.provRows + ", workitem_archive=" + r.archiveRows
                        + ", certification_item=" + r.certItemRows);
                if (!r.sourcesMissing.isEmpty()) {
                    System.out.println("  source tables absent (no links derived from them): " + r.sourcesMissing);
                }
                if (r.eventTableMissing) {
                    System.out.println("  kf_event is absent: no link can be written (run derive-native-events first)");
                }
                System.out.println("Links derived: " + r.linksDerived + " (inserted " + r.linksInserted
                        + ", already present " + r.linksAlreadyPresent + ", event not in kf_event "
                        + r.linksWithoutEvent + ") -> " + svc.linkTable());
                for (Map.Entry<String, Integer> e : r.derivedByType.entrySet()) {
                    System.out.println("    " + e.getKey() + ": " + e.getValue() + " (inserted "
                            + r.insertedByType.getOrDefault(e.getKey(), 0) + ")");
                }
                if (r.linksWithoutEvent > 0) {
                    System.out.println("  " + r.linksWithoutEvent + " link(s) not written because their event is not "
                            + "stored; run derive-native-events, then this command again.");
                }
                return 0;
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            RunLedger.error("configuration error: " + e.getMessage());
            return 3;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error (the failing chunk was rolled back; chunks already committed stay, "
                    + "and a re-run completes the rest): " + e.getMessage());
            RunLedger.error("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            RunLedger.error("unexpected error: " + e.getMessage());
            return 1;
        }
    }

    /** Runs a generic {@link EntitySpec} import with the same config, summary and exit codes as Identity. */
    private static int runImport(EntitySpec spec) {
        return runImport(schema -> new EntityRepository(schema, spec));
    }

    private static int runImport(EntitySpec spec, FetchOptions options) {
        return runImport(schema -> new EntityRepository(schema, spec), options);
    }

    /** Runs an import through the writer built for the configured destination schema. */
    private static int runImport(Function<String, EntityWriter> writerForSchema) {
        return runImport(writerForSchema, FetchOptions.none());
    }

    private static int runImport(Function<String, EntityWriter> writerForSchema, FetchOptions options) {
        try {
            AppConfig config = AppConfig.load();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            EntityWriter writer = writerForSchema.apply(config.pgSchema());

            try (Connection conn = PostgresConnection.open(config)) {
                // Full runs page start/limit until a short page (Part 1 serves each window directly, no counted
                // whole-entity read); caller-scoped runs fetch exactly the requested window or filter.
                EntityImportService importer = new EntityImportService(client, writer, config.pageSize(),
                        config.sweepMaxDeletePercent());
                ImportResult result = options.isEmpty() ? importer.run(conn, runId)
                        : importer.run(conn, runId, options);
                SoftDeleteSweeper.Result sweep = importer.lastSweep();
                RunLedger.record(counts(writer.targetTable(), result, sweep));
                System.out.println();
                System.out.println(result.summary());
                if (!options.isEmpty()) {
                    System.out.println("  fetch:     caller-scoped partial import; no row marked deleted");
                } else if (sweep != null) {
                    return printDeletions(sweep, result.failed());
                }
                return result.failed() > 0 ? 6 : 0;
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            RunLedger.error("configuration error: " + e.getMessage());
            return 3;
        } catch (KfAgentClient.KfAgentHttpException e) {
            System.err.println("KF Agent HTTP/source error: " + e.getMessage());
            RunLedger.error("KF Agent HTTP/source error: " + e.getMessage());
            return 4;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            RunLedger.error("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            RunLedger.error("unexpected error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Runs the syslog import: streams /kfagent/syslog-events page by page (append-only) until a short page. Page reads
     * carry no X-KF-Source-Count; when a page does report one, a scan that does not reach it (or a count that shrinks)
     * exits 7 with the partial counts. Rows already appended are kept (immutable, skipped on re-run).
     */
    private static int runSyslogImport() {
        try {
            AppConfig config = AppConfig.load();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            SyslogEventRepository syslog = new SyslogEventRepository(config.pgSchema());
            SyslogEventImportService service = new SyslogEventImportService(client::fetch, syslog, config.pageSize());

            try (Connection conn = PostgresConnection.open(config)) {
                try {
                    ImportResult result = service.run(conn, runId);
                    RunLedger.record(counts(syslog.targetTable(), result, null));
                    System.out.println();
                    System.out.println(result.summary());
                    printSyslogScan(service, "complete");
                    return result.failed() > 0 ? 6 : 0;
                } catch (SyslogEventImportService.IncompleteScanException e) {
                    System.out.println();
                    RunLedger.record(counts(syslog.targetTable(), e.partial(), null));
                    System.out.println(e.partial().summary());
                    printSyslogScan(service, "NOT CONFIRMED COMPLETE");
                    System.err.println("Incomplete scan: " + e.getMessage());
                    RunLedger.error("incomplete scan: " + e.getMessage());
                    return 7;
                }
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            RunLedger.error("configuration error: " + e.getMessage());
            return 3;
        } catch (KfAgentClient.KfAgentHttpException e) {
            System.err.println("KF Agent HTTP/source error: " + e.getMessage());
            RunLedger.error("KF Agent HTTP/source error: " + e.getMessage());
            return 4;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            RunLedger.error("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            RunLedger.error("unexpected error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Prints what a full run's deletion marking did. Exit 7 when the safety limit stopped it (the deletion state may be
     * stale and the source should be checked); otherwise the row-failure exit code.
     */
    static int printDeletions(SoftDeleteSweeper.Result sweep, int failedRows) {
        if (sweep.swept()) {
            System.out.println("  deleted:   marked " + sweep.marked() + " row(s) the source no longer returns (of "
                    + sweep.keepCount() + " rows returned)");
        } else {
            System.out.println("  deleted:   not marked — " + sweep.note());
        }
        if (sweep.status() == SoftDeleteSweeper.Status.SKIPPED_SAFETY_LIMIT) {
            System.err.println("Deletion marking skipped: " + sweep.note());
            RunLedger.error("deletion marking skipped: " + sweep.note());
            return 7;
        }
        return failedRows > 0 ? 6 : 0;
    }

    /** One table's ledger counts (unqualified table name), with the sweep outcome when the table is swept. */
    static RunContext.EntityCount counts(String qualifiedTable, ImportResult r, SoftDeleteSweeper.Result sweep) {
        String table = qualifiedTable.substring(qualifiedTable.lastIndexOf('.') + 1);
        return new RunContext.EntityCount(table, r.fetched(), r.inserted(), r.updated(), r.failed(), r.skipped(),
                sweep == null ? null : sweep.status().name(),
                sweep == null ? null : sweep.marked(), sweep == null ? null : sweep.revived());
    }

    /**
     * Incremental parent + children (see {@link FamilyIncrementalImportService}): every dataset is fetched with the same
     * bound before anything is written, and the shared watermark advances only if no row failed in any of them.
     */
    private static int runFamilyIncremental(IncrementalFamily family) {
        try {
            AppConfig config = AppConfig.load();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            EntityWriter parent = family.parent().apply(config.pgSchema());
            List<EntityWriter> children = new ArrayList<>();
            for (Function<String, EntityWriter> c : family.children()) {
                children.add(c.apply(config.pgSchema()));
            }
            Duration overlap = IncrementalConfig.overlapWindow();
            FamilyIncrementalImportService service = new FamilyIncrementalImportService(pagedFetcher(client),
                    parent, children, new WatermarkService(config.pgSchema()), overlap, config.pageSize(),
                    java.time.Clock.systemUTC());

            try (Connection conn = PostgresConnection.open(config)) {
                FamilyIncrementalImportService.Outcome o = service.run(conn, runId);
                RunLedger.window(o.bound(), o.fetchedAt());
                for (ImportResult r : o.results()) {
                    RunLedger.record(counts(r.targetTable(), r, null));
                }
                for (ImportResult r : o.results()) {
                    System.out.println();
                    System.out.println(r.summary());
                }
                System.out.println("  mode:      incremental, parent + children (overlap " + overlap.toMinutes()
                        + " min); watermark " + o.key() + " was "
                        + (o.stored() == null ? "unset -> full fetch (baseline)" : o.stored())
                        + "; modifiedAfter=" + (o.bound() == null ? "none" : o.bound())
                        + " (IIQ modified, or created if never modified)");
                System.out.println("  watermark: " + (o.advanced() ? "advanced to " + o.newWatermark()
                        : "NOT advanced (" + (o.failed() > 0 ? o.failed()
                        + " row failure(s) in the family; the next run re-reads from the unchanged watermark)"
                        : "no newer change time than the stored watermark") + ")"));
                System.out.println("  sweep:     not run (an incremental import never marks rows deleted)");
                if (o.failed() > 0) {
                    RunLedger.error("watermark not advanced: " + o.failed() + " row failure(s)");
                }
                return o.failed() > 0 ? 6 : 0;
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            RunLedger.error("configuration error: " + e.getMessage());
            return 3;
        } catch (KfAgentClient.KfAgentHttpException e) {
            System.err.println("KF Agent HTTP/source error: " + e.getMessage());
            RunLedger.error("KF Agent HTTP/source error: " + e.getMessage());
            return 4;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            RunLedger.error("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            RunLedger.error("unexpected error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Incremental syslog: the same paged import as {@link #runSyslogImport()}, with every page bounded to events
     * created after the stored watermark minus the overlap window (no watermark = a full paged read that sets the
     * baseline). The watermark ({@code max(created_at)}, capped at the run's start) advances only when the run had no
     * row failures; an incomplete scan exits 7 and leaves it unchanged. Never sweeps.
     */
    private static int runSyslogIncremental() {
        try {
            AppConfig config = AppConfig.load();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            SyslogEventRepository syslog = new SyslogEventRepository(config.pgSchema());
            WatermarkService watermarks = new WatermarkService(config.pgSchema());
            Duration overlap = IncrementalConfig.overlapWindow();

            try (Connection conn = PostgresConnection.open(config)) {
                conn.setAutoCommit(true);
                Instant stored = watermarks.read(conn, syslog.targetTable());
                Instant bound = WatermarkService.effectiveBound(stored, ExtractionMode.INCREMENTAL, overlap);
                String boundIso = bound == null ? null : bound.toString();
                Instant fetchedAt = Instant.now();
                SyslogEventImportService service = new SyslogEventImportService(
                        (resource, start, limit) -> client.fetch(resource, start, limit, boundIso),
                        syslog, config.pageSize());
                RunLedger.window(bound, fetchedAt);
                String mode = "  mode:      incremental (overlap " + overlap.toMinutes() + " min); watermark "
                        + WatermarkService.watermarkKey(syslog.targetTable()) + " was "
                        + (stored == null ? "unset -> full scan (baseline)" : stored)
                        + "; modifiedAfter=" + (bound == null ? "none" : bound) + " (IIQ created)";
                ImportResult result;
                try {
                    result = service.run(conn, runId);
                } catch (SyslogEventImportService.IncompleteScanException e) {
                    System.out.println();
                    RunLedger.record(counts(syslog.targetTable(), e.partial(), null));
                    System.out.println(e.partial().summary());
                    printSyslogScan(service, "NOT CONFIRMED COMPLETE");
                    System.out.println(mode);
                    System.out.println("  watermark: NOT advanced (scan not confirmed complete)");
                    System.err.println("Incomplete scan: " + e.getMessage());
                    RunLedger.error("incomplete scan: " + e.getMessage());
                    return 7;
                }
                Instant newWatermark = WatermarkService.capAtRunStart(
                        watermarks.maxValue(conn, syslog.targetTable(), IncrementalCursor.CREATED), fetchedAt);
                boolean advanced = watermarks.advanceIfSuccessful(conn, syslog.targetTable(), newWatermark,
                        result.failed(), runId, IncrementalCursor.CREATED);
                RunLedger.record(counts(syslog.targetTable(), result, null));
                System.out.println();
                System.out.println(result.summary());
                printSyslogScan(service, "complete");
                System.out.println(mode);
                System.out.println("  watermark: " + (advanced ? "advanced to " + newWatermark
                        : "NOT advanced (" + (result.failed() > 0 ? result.failed()
                        + " row failure(s); the next run re-reads from the unchanged watermark)"
                        : "no newer event time than the stored watermark") + ")"));
                System.out.println("  sweep:     not run (append-only table)");
                if (result.failed() > 0) {
                    RunLedger.error("watermark not advanced: " + result.failed() + " row failure(s)");
                }
                return result.failed() > 0 ? 6 : 0;
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            RunLedger.error("configuration error: " + e.getMessage());
            return 3;
        } catch (KfAgentClient.KfAgentHttpException e) {
            System.err.println("KF Agent HTTP/source error: " + e.getMessage());
            RunLedger.error("KF Agent HTTP/source error: " + e.getMessage());
            return 4;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            RunLedger.error("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            RunLedger.error("unexpected error: " + e.getMessage());
            return 1;
        }
    }

    private static void printSyslogScan(SyslogEventImportService service, String status) {
        String counts = service.baselineSourceCount() < 0
                ? "no source count reported by the paged reads"
                : "baseline source count " + service.baselineSourceCount() + ", highest "
                + service.highWaterSourceCount();
        System.out.println("  scan:      " + status + " (" + counts + ", distinct in-order rows "
                + service.inOrderRows() + ", overlapping/out-of-order " + service.overlapRows() + ")");
    }

    /**
     * Runs a SQL-report import into the report's keyed table ({@link SqlReportImportService}): a full run pages through
     * the report and then marks rows it no longer returns as deleted; a caller-scoped run ({@code start}/{@code limit},
     * filters, {@code modifiedAfter}) writes exactly that one response. Every row is inserted, updated or skipped by its
     * uuid. Same config, exit codes and summary as the entity imports.
     */
    private static int runSqlReport(SqlReportSpec spec, FetchOptions options) {
        FetchOptions fetchOptions = options == null ? FetchOptions.none() : options;
        try {
            AppConfig config = AppConfig.load();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            try (Connection conn = PostgresConnection.open(config)) {
                SqlReportRepository repository = new SqlReportRepository(config.pgSchema(), spec);
                SqlReportImportService service = new SqlReportImportService(client::fetchPage, repository,
                        config.reportPageSize(), config.sweepMaxDeletePercent());
                SqlReportImportService.Outcome o = service.run(conn, runId, fetchOptions);
                RunLedger.record(counts(repository.targetTable(), o.result(), o.sweep()));
                System.out.println();
                System.out.println(o.result().summary());
                if (o.sweep() == null) {
                    System.out.println("  fetch:     caller-scoped partial import; no row marked deleted");
                    return o.result().failed() > 0 ? 6 : 0;
                }
                return printDeletions(o.sweep(), o.result().failed());
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            RunLedger.error("configuration error: " + e.getMessage());
            return 3;
        } catch (KfAgentClient.KfAgentHttpException e) {
            System.err.println("KF Agent HTTP/source error: " + e.getMessage());
            RunLedger.error("KF Agent HTTP/source error: " + e.getMessage());
            return 4;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            RunLedger.error("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            RunLedger.error("unexpected error: " + e.getMessage());
            return 1;
        }
    }

    private static void printUsage() {
        System.out.println("KF Agent -> PostgreSQL ingestion (Part 2)");
        System.out.println("Usage: java -jar kfagent-ingest.jar <command>");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  import-identity      Fetch GET /kfagent/identities   and upsert into <PG_DEST_SCHEMA>.kf_identity");
        System.out.println("  import-entitlement   Fetch GET /kfagent/entitlements and upsert into <PG_DEST_SCHEMA>.kf_entitlement");
        System.out.println("  import-application   Fetch GET /kfagent/applications and upsert into <PG_DEST_SCHEMA>.kf_application");
        System.out.println("  import-account       Fetch GET /kfagent/accounts     and upsert into <PG_DEST_SCHEMA>.kf_account");
        System.out.println("  import-workgroup     Fetch GET /kfagent/workgroups   and upsert into <PG_DEST_SCHEMA>.kf_workgroup");
        System.out.println("  import-role          Fetch GET /kfagent/roles        and upsert into <PG_DEST_SCHEMA>.kf_role");
        System.out.println("  import-identity-role Fetch GET /kfagent/identity-roles and upsert into <PG_DEST_SCHEMA>.kf_identity_role");
        System.out.println("  import-identity-entitlement");
        System.out.println("                       Fetch GET /kfagent/identity-entitlements and upsert into <PG_DEST_SCHEMA>.kf_identity_entitlement");
        System.out.println("  import-account-entitlement");
        System.out.println("                       Fetch GET /kfagent/account-entitlements and upsert into <PG_DEST_SCHEMA>.kf_account_entitlement");
        System.out.println("  import-role-entitlement");
        System.out.println("                       Fetch GET /kfagent/role-entitlements and upsert into <PG_DEST_SCHEMA>.kf_role_entitlement");
        System.out.println("                       (needs kf_entitlement in the same schema: run import-entitlement first)");
        System.out.println("  import-role-hierarchy");
        System.out.println("                       Fetch GET /kfagent/role-hierarchy and upsert into <PG_DEST_SCHEMA>.kf_role_hierarchy");
        System.out.println("  import-identity-request");
        System.out.println("                       Fetch GET /kfagent/identity-requests and upsert into <PG_DEST_SCHEMA>.kf_identity_request");
        System.out.println("  import-identity-request-item");
        System.out.println("                       Fetch GET /kfagent/identity-request-items and upsert into <PG_DEST_SCHEMA>.kf_identity_request_item");
        System.out.println("  import-identity-request-approval");
        System.out.println("                       Fetch GET /kfagent/identity-request-approvals and upsert into <PG_DEST_SCHEMA>.kf_identity_request_approval");
        System.out.println("  import-policy        Fetch GET /kfagent/policies     and upsert into <PG_DEST_SCHEMA>.kf_policy");
        System.out.println("  import-provisioning-item");
        System.out.println("                       Fetch GET /kfagent/provisioning-items and upsert into <PG_DEST_SCHEMA>.kf_provisioning_item");
        System.out.println("  import-provisioning-transaction");
        System.out.println("                       Fetch GET /kfagent/provisioning-transactions and upsert into <PG_DEST_SCHEMA>.kf_provisioning_txn");
        System.out.println("  import-work-item     Fetch GET /kfagent/work-items   and upsert into <PG_DEST_SCHEMA>.kf_workitem");
        System.out.println("  import-workflow-definition");
        System.out.println("                       Fetch GET /kfagent/workflow-definitions and upsert into <PG_DEST_SCHEMA>.kf_workflow_definition");
        System.out.println("  import-certification Fetch GET /kfagent/certifications and upsert into <PG_DEST_SCHEMA>.kf_certification");
        System.out.println("  import-certification-entity");
        System.out.println("                       Fetch GET /kfagent/certification-entities and upsert into <PG_DEST_SCHEMA>.kf_certification_entity");
        System.out.println("  import-certification-item");
        System.out.println("                       Fetch GET /kfagent/certification-items and upsert into <PG_DEST_SCHEMA>.kf_certification_item");
        System.out.println("  import-certification-archive");
        System.out.println("                       Fetch GET /kfagent/certification-archives and append into <PG_DEST_SCHEMA>.kf_certification_archive");
        System.out.println("  import-policy-constraint");
        System.out.println("                       Fetch GET /kfagent/policy-constraints and upsert into <PG_DEST_SCHEMA>.kf_policy_constraint");
        System.out.println("  import-policy-violation");
        System.out.println("                       Fetch GET /kfagent/policy-violations and upsert into <PG_DEST_SCHEMA>.kf_violation");
        System.out.println("  import-entitlement-assignment");
        System.out.println("                       Fetch GET /kfagent/entitlement-assignment (SQL report) and upsert into <PG_DEST_SCHEMA>.kf_entitlement_assignment");
        System.out.println("  import-workgroup-member");
        System.out.println("                       Fetch GET /kfagent/workgroupmember and upsert into <PG_DEST_SCHEMA>.kf_workgroup_member");
        System.out.println("  import-entitlement-certification");
        System.out.println("                       Fetch GET /kfagent/entitlement-certification (SQL report) and upsert into <PG_DEST_SCHEMA>.kf_entitlement_certification");
        System.out.println("  import-entitlement-certification-status");
        System.out.println("                       Fetch GET /kfagent/entitlement-certification-status (SQL report) and upsert into <PG_DEST_SCHEMA>.kf_entitlement_certification_status");
        System.out.println("  import-workgroup-members");
        System.out.println("                       Fetch GET /kfagent/workgroup-members (SQL report) and upsert into <PG_DEST_SCHEMA>.kf_workgroup_members");
        System.out.println("  import-audit-event");
        System.out.println("                       Fetch GET /kfagent/audit-events and append into <PG_DEST_SCHEMA>.kf_audit_event");
        System.out.println("  import-task-result");
        System.out.println("                       Fetch GET /kfagent/task-results and upsert into <PG_DEST_SCHEMA>.kf_task_result");
        System.out.println("  import-task-schedule");
        System.out.println("                       Fetch GET /kfagent/task-schedules and upsert into <PG_DEST_SCHEMA>.kf_task_schedule");
        System.out.println("  import-workitem-archive");
        System.out.println("                       Fetch GET /kfagent/workitem-archives and append into <PG_DEST_SCHEMA>.kf_workitem_archive");
        System.out.println("  import-group-definition");
        System.out.println("                       Fetch GET /kfagent/group-definitions and upsert into <PG_DEST_SCHEMA>.kf_group_definition");
        System.out.println("  import-access-history");
        System.out.println("                       Fetch GET /kfagent/access-history and append into <PG_DEST_SCHEMA>.kf_access_hist_entitlement");
        System.out.println("  import-access-history-identity-event");
        System.out.println("                       Fetch GET /kfagent/access-history/identity-events and append into <PG_DEST_SCHEMA>.kf_access_hist_identity_event");
        System.out.println("  import-access-history-certification");
        System.out.println("                       Fetch GET /kfagent/access-history/certifications and append into <PG_DEST_SCHEMA>.kf_access_hist_certification");
        System.out.println("  import-access-history-role-event");
        System.out.println("                       Fetch GET /kfagent/access-history/role-events and append into <PG_DEST_SCHEMA>.kf_access_hist_role_event");
        System.out.println("  import-syslog-event");
        System.out.println("                       Page GET /kfagent/syslog-events and append into <PG_DEST_SCHEMA>.kf_syslog_event");
        System.out.println();
        System.out.println("  reconcile-native-workitems");
        System.out.println("                       Reconcile loaded approval work_item_id references against kf_workitem +");
        System.out.println("                       kf_workitem_archive; record one finding per run in <PG_DEST_SCHEMA>.kf_reconciliation_finding");
        System.out.println("  derive-native-events");
        System.out.println("                       Derive append-only events from loaded approval / task-result / provisioning-txn /");
        System.out.println("                       workitem-archive / certification-item tables into <PG_DEST_SCHEMA>.kf_event");
        System.out.println("                       (PostgreSQL only; KFAGENT_BASE_URL not required; start/limit per source table,");
        System.out.println("                       or --incremental for the rows written since the last derivation)");
        System.out.println("  derive-native-event-links");
        System.out.println("                       Derive append-only EXPLICIT links for events already in kf_event into");
        System.out.println("                       <PG_DEST_SCHEMA>.kf_event_link (PostgreSQL only; run after derive-native-events;");
        System.out.println("                       start/limit per source table, or --incremental)");
        System.out.println();
        System.out.println("Incremental (opt-in): add --incremental to fetch only what changed since the last successful run");
        System.out.println("                       (per-entity watermark lowered by INCREMENTAL_OVERLAP_MINUTES, default 5):");
        System.out.println("                       - changed (modified, or created and never modified) after the watermark:");
        System.out.println("                         import-identity, import-entitlement, import-application, import-account,");
        System.out.println("                         import-role, import-work-item, import-task-result, import-certification-item,");
        System.out.println("                         import-certification-entity, import-identity-entitlement");
        System.out.println("                       - parent + children under one shared watermark: import-identity-request");
        System.out.println("                         (+ request items, approvals), import-provisioning-transaction (+ provisioning");
        System.out.println("                         items), import-account-entitlement (+ the changed accounts)");
        System.out.println("                       - created after the watermark: import-audit-event, import-syslog-event,");
        System.out.println("                         import-workitem-archive, import-certification-archive, import-access-history*");
        System.out.println("                       Incremental runs never sweep; full mode stays the default.");
        System.out.println();
        System.out.println("REST mode: start-rest   Serve GET /kfagent-ingest/<command>[?mode=incremental] (runs the same command;");
        System.out.println("                       one at a time). REST_HOST (127.0.0.1), REST_PORT (8200); every request uses");
        System.out.println("                       HTTP Basic Auth with KFAGENT_USERNAME / KFAGENT_PASSWORD (both required)");
        System.out.println();
        System.out.println("Required env: KFAGENT_BASE_URL, KFAGENT_USERNAME, KFAGENT_PASSWORD, PG_HOST, PG_DATABASE,");
        System.out.println("              PG_USERNAME, PG_PASSWORD (derive-* and reconcile-* need only the PG_* settings)");
        System.out.println("Optional env: PG_PORT (5432), PG_DEST_SCHEMA (iiq_native_part2), KFAGENT_PAGE_SIZE (500),");
        System.out.println("              KFAGENT_REPORT_PAGE_SIZE (200000), HTTP_TIMEOUT_SECONDS (60)");
    }
}

package com.keyforge.part2;

import com.keyforge.part2.config.AppConfig;
import com.keyforge.part2.event.EventDerivationService;
import com.keyforge.part2.event.EventLinkDerivationService;
import com.keyforge.part2.event.EventLinkRepository;
import com.keyforge.part2.event.EventRepository;
import com.keyforge.part2.config.ConfigException;
import com.keyforge.part2.db.PostgresConnection;
import com.keyforge.part2.http.KfAgentClient;
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
import com.keyforge.part2.ingest.IdentityImportService;
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
import com.keyforge.part2.ingest.SnapshotSweepImportService;
import com.keyforge.part2.ingest.SoftDeleteSweeper;
import com.keyforge.part2.ingest.SqlReportImportService;
import com.keyforge.part2.ingest.SweepPolicies;
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
 *                       Fetch GET /kfagent/entitlement-assignment (SQL report) and REPLACE &lt;PG_DEST_SCHEMA&gt;.kf_entitlement_assignment
 *  import-workgroup-member
 *                       Fetch GET /kfagent/workgroupmember and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_workgroup_member
 *  import-entitlement-certification
 *                       Fetch GET /kfagent/entitlement-certification (SQL report) and REPLACE &lt;PG_DEST_SCHEMA&gt;.kf_entitlement_certification
 *  import-entitlement-certification-status
 *                       Fetch GET /kfagent/entitlement-certification-status (SQL report) and REPLACE &lt;PG_DEST_SCHEMA&gt;.kf_entitlement_certification_status
 *  import-workgroup-members
 *                       Fetch GET /kfagent/workgroup-members (SQL report) and REPLACE &lt;PG_DEST_SCHEMA&gt;.kf_workgroup_members
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
 *                       Page GET /kfagent/syslog-events (complete-scan guarded) and append into &lt;PG_DEST_SCHEMA&gt;.kf_syslog_event</pre>
 *
 * Configuration is entirely external (environment variables / -D system properties) — see {@link AppConfig}.
 * Exit codes: 0 ok, 2 usage, 3 config error, 4 HTTP/source error, 5 PostgreSQL error, 6 completed with row
 * failures, 7 scan not confirmed complete (import-syslog-event; or a swept table whose snapshot completeness could
 * not be proven, so its deletion sweep was skipped), 1 unexpected.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String command = args.length == 0 ? "" : args[0].trim();
        ExtractionMode mode = ExtractionMode.fromArgs(java.util.Arrays.copyOfRange(args, Math.min(1, args.length),
                args.length));
        String ledgerCommand = mode.isIncremental() ? command + " --incremental" : command;
        System.exit(RunLedger.run(ledgerCommand, () -> dispatch(command, mode)));
    }

    /** Runs one full-mode command (see {@link #dispatch(String, ExtractionMode)}). */
    static int dispatch(String command) {
        return dispatch(command, ExtractionMode.FULL);
    }

    /**
     * Runs one command and returns its exit code; every import is recorded in kf_extraction_run. Incremental mode is
     * opt-in ({@code --incremental}) and only for the entities whose KF Agent endpoint supports {@code modifiedAfter}.
     */
    static int dispatch(String command, ExtractionMode mode) {
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
            RunLedger.discard(); // a usage error is not an import run
            System.err.println("--incremental is supported only for " + INCREMENTAL_WRITERS.keySet() + ", "
                    + INCREMENTAL_FAMILIES.keySet() + ", " + CHANGED_INCREMENTAL_WRITERS.keySet() + ", "
                    + EVENT_INCREMENTAL_WRITERS.keySet() + " and " + SYSLOG_COMMAND
                    + " (their KF Agent endpoints accept modifiedAfter); '" + command + "' runs in full mode only.");
            return 2;
        }
        return switch (command) {
            case "import-identity" -> runImportIdentity();
            case "import-entitlement" -> runImport(EntitlementTable.SPEC);
            case "import-application" -> runImport(ApplicationTable.SPEC);
            case "import-account" -> runImport(AccountTable.SPEC);
            case "import-workgroup" -> runImport(WorkgroupTable.SPEC);
            case "import-role" -> runImport(RoleTable.SPEC);
            case "import-identity-role" -> runImport(IdentityRoleTable.SPEC);
            case "import-identity-entitlement" -> runImport(IdentityEntitlementTable.SPEC);
            case "import-account-entitlement" -> runImport(AccountEntitlementTable.SPEC);
            case "import-role-entitlement" -> runImport(RoleEntitlementRepository::new);
            case "import-role-hierarchy" -> runImport(RoleHierarchyRepository::new);
            case "import-identity-request" -> runImport(IdentityRequestTable.SPEC);
            case "import-identity-request-item" -> runImport(IdentityRequestItemTable.SPEC);
            case "import-identity-request-approval" -> runImport(IdentityRequestApprovalTable.SPEC);
            case "import-policy" -> runImport(PolicyTable.SPEC);
            case "import-provisioning-item" -> runImport(ProvisioningItemRepository::new);
            case "import-provisioning-transaction" -> runImport(ProvisioningTxnTable.SPEC);
            case "import-work-item" -> runImport(WorkItemTable.SPEC);
            case "import-workflow-definition" -> runImport(WorkflowDefinitionRepository::new);
            case "import-certification" -> runImport(CertificationTable.SPEC);
            case "import-certification-entity" -> runImport(CertificationEntityTable.SPEC);
            case "import-certification-item" -> runImport(CertificationItemTable.SPEC);
            case "import-certification-archive" -> runImport(CertificationArchiveRepository::new);
            case "import-policy-constraint" -> runImport(PolicyConstraintTable.SPEC);
            case "import-policy-violation" -> runImport(PolicyViolationTable.SPEC);
            case "import-entitlement-assignment" -> runSqlReport("EntitlementAssignment",
                    EntitlementAssignmentReport.RESOURCE, EntitlementAssignmentReport.TABLE,
                    EntitlementAssignmentReport.COLUMNS);
            case "import-workgroup-member" -> runImport(WorkgroupMemberRepository::new);
            case "import-entitlement-certification" -> runSqlReport("EntitlementCertification",
                    EntitlementCertificationReport.RESOURCE, EntitlementCertificationReport.TABLE,
                    EntitlementCertificationReport.COLUMNS);
            case "import-entitlement-certification-status" -> runSqlReport("EntitlementCertificationStatus",
                    EntitlementCertificationStatusReport.RESOURCE, EntitlementCertificationStatusReport.TABLE,
                    EntitlementCertificationStatusReport.COLUMNS);
            case "import-workgroup-members" -> runSqlReport("WorkgroupMembers",
                    WorkgroupMembersReport.RESOURCE, WorkgroupMembersReport.TABLE, WorkgroupMembersReport.COLUMNS);
            case "import-audit-event" -> runImport(AuditEventRepository::new);
            case "import-task-result" -> runImport(TaskResultTable.SPEC);
            case "import-task-schedule" -> runImport(TaskScheduleTable.SPEC);
            case "import-workitem-archive" -> runImport(WorkItemArchiveRepository::new);
            case "import-group-definition" -> runImport(GroupDefinitionTable.SPEC);
            case "import-access-history" -> runImport(AccessHistoryEntitlementRepository::new);
            case "import-access-history-identity-event" ->
                    runImport(AccessHistoryIdentityEventRepository::new);
            case "import-access-history-certification" ->
                    runImport(AccessHistoryCertificationRepository::new);
            case "import-access-history-role-event" ->
                    runImport(AccessHistoryRoleEventRepository::new);
            case "import-syslog-event" -> runSyslogImport();
            case "reconcile-native-workitems" -> runReconcileWorkItems();
            case "derive-native-events" -> runDeriveNativeEvents();
            case "derive-native-event-links" -> runDeriveNativeEventLinks();
            default -> {
                RunLedger.discard(); // a usage error is not an import run
                printUsage();
                yield 2;
            }
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

    /** The Identity table's dedicated repository as an {@link EntityWriter} (incremental path only). */
    static EntityWriter identityWriter(String schema) {
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
                return repository.upsert(conn, row, runId) == IdentityRepository.Outcome.INSERTED
                        ? EntityRepository.Outcome.INSERTED : EntityRepository.Outcome.UPDATED;
            }
            @Override public String ref(JsonNode row) {
                return "identity " + row.path("name").asText(null) + " (" + row.path("source_id").asText(null) + ")";
            }
        };
    }

    /**
     * Incremental import (opt-in): rows modified after the stored watermark minus the overlap, one KF Agent request,
     * the ordinary writer, and a watermark advance only when no row failed. Never sweeps.
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
            IncrementalImportService service = new IncrementalImportService(client::fetchSnapshot, writer,
                    new WatermarkService(config.pgSchema()), overlap, cursor);

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
            AppConfig config = AppConfig.load();
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
    private static int runDeriveNativeEvents() {
        try {
            AppConfig config = AppConfig.loadDatabaseOnly();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);
            System.out.println("Native event derivation (append-only; reads loaded tables only, no KF Agent call): "
                    + "kf_identity_request_approval, kf_task_result, kf_provisioning_txn, kf_workitem_archive, "
                    + "kf_certification_item -> kf_event");
            try (Connection conn = PostgresConnection.open(config)) {
                EventDerivationService svc = new EventDerivationService(config.pgSchema());
                EventDerivationService.Result r = svc.derive(conn, runId);
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
            System.err.println("PostgreSQL error (derivation rolled back, kf_event unchanged): " + e.getMessage());
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
    private static int runDeriveNativeEventLinks() {
        try {
            AppConfig config = AppConfig.loadDatabaseOnly();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);
            System.out.println("Native event-link derivation (append-only, EXPLICIT ids only; reads loaded tables and "
                    + "kf_event, no KF Agent call) -> kf_event_link");
            try (Connection conn = PostgresConnection.open(config)) {
                EventLinkDerivationService svc = new EventLinkDerivationService(config.pgSchema());
                EventLinkDerivationService.Result r = svc.derive(conn, runId);
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
            System.err.println("PostgreSQL error (link derivation rolled back, kf_event_link unchanged): "
                    + e.getMessage());
            RunLedger.error("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            RunLedger.error("unexpected error: " + e.getMessage());
            return 1;
        }
    }

    private static int runImportIdentity() {
        try {
            AppConfig config = AppConfig.load();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            IdentityRepository repository = new IdentityRepository(config.pgSchema());
            IdentityImportService service = new IdentityImportService(client, repository, config.pageSize());

            try (Connection conn = PostgresConnection.open(config)) {
                ImportResult result = service.run(conn, runId);
                RunLedger.record(counts(repository.targetTable(), result, null));
                System.out.println();
                System.out.println(result.summary());
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

    /** Runs a generic {@link EntitySpec} import with the same config, summary and exit codes as Identity. */
    private static int runImport(EntitySpec spec) {
        return runImport(schema -> new EntityRepository(schema, spec));
    }

    /** Runs an import through the writer built for the configured destination schema. */
    private static int runImport(Function<String, EntityWriter> writerForSchema) {
        try {
            AppConfig config = AppConfig.load();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            EntityWriter writer = writerForSchema.apply(config.pgSchema());
            Optional<SweepPolicies.SweepConfig> sweep = SweepPolicies.configFor(writer.targetTable());

            try (Connection conn = PostgresConnection.open(config)) {
                if (sweep.isEmpty()) {
                    // not swept: the ordinary paged import, unchanged
                    ImportResult result = new EntityImportService(client, writer, config.pageSize()).run(conn, runId);
                    RunLedger.record(counts(writer.targetTable(), result, null));
                    System.out.println();
                    System.out.println(result.summary());
                    return result.failed() > 0 ? 6 : 0;
                }
                SnapshotSweepImportService.Outcome outcome = new SnapshotSweepImportService(client::fetchSnapshot,
                        writer, sweep.get(), new SoftDeleteSweeper(config.pgSchema()), false).run(conn, runId);
                RunLedger.record(counts(writer.targetTable(), outcome.result(), outcome.sweep()));
                System.out.println();
                System.out.println(outcome.result().summary());
                return printSweep(outcome.sweep(), outcome.result().failed());
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
     * Runs the syslog import: streams /kfagent/syslog-events page by page (append-only) and confirms the scan is
     * complete against the agent's X-KF-Source-Count. A scan that cannot be confirmed complete exits 7 with the
     * partial counts; rows already appended are kept (immutable, skipped on re-run).
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

    /** One table's ledger counts (unqualified table name), with the sweep outcome when the table is swept. */
    static RunContext.EntityCount counts(String qualifiedTable, ImportResult r, SoftDeleteSweeper.Result sweep) {
        String table = qualifiedTable.substring(qualifiedTable.lastIndexOf('.') + 1);
        return new RunContext.EntityCount(table, r.fetched(), r.inserted(), r.updated(), r.failed(), r.skipped(),
                sweep == null ? null : sweep.status().name(),
                sweep == null ? null : sweep.marked(), sweep == null ? null : sweep.revived());
    }

    /** Prints the sweep line; exit 7 when completeness could not be proven (the deletion state may be stale). */
    private static int printSweep(SoftDeleteSweeper.Result sweep, int failedRows) {
        if (sweep.swept()) {
            System.out.println("  sweep:     marked deleted " + sweep.marked() + ", revived " + sweep.revived()
                    + " (keep-set " + sweep.keepCount() + "; " + sweep.note() + ")");
        } else {
            System.out.println("  sweep:     " + sweep.status() + " — " + sweep.note()
                    + " (no row was marked deleted or revived)");
        }
        boolean unproven = sweep.status() == SoftDeleteSweeper.Status.SKIPPED_COMPLETENESS_FAILED
                || sweep.status() == SoftDeleteSweeper.Status.SKIPPED_NOT_VERIFIED;
        if (unproven) {
            System.err.println("Deletion sweep skipped: snapshot completeness not proven — " + sweep.note());
            RunLedger.error("deletion sweep skipped: " + sweep.note());
            return 7;
        }
        return failedRows > 0 ? 6 : 0;
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
            FamilyIncrementalImportService service = new FamilyIncrementalImportService(client::fetchSnapshot,
                    parent, children, new WatermarkService(config.pgSchema()), overlap);

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
     * Incremental syslog: the same paged, complete-scan-guarded import as {@link #runSyslogImport()}, with every
     * page bounded to events created after the stored watermark minus the overlap window (no watermark = a full
     * scan that sets the baseline). The watermark ({@code max(created_at)}) advances only after a scan confirmed
     * complete with no row failures; an incomplete scan exits 7 and leaves it unchanged. Never sweeps.
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
                Instant newWatermark = watermarks.maxValue(conn, syslog.targetTable(), IncrementalCursor.CREATED);
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
        System.out.println("  scan:      " + status + " (baseline source count " + service.baselineSourceCount()
                + ", highest " + service.highWaterSourceCount() + ", distinct in-order rows "
                + service.inOrderRows() + ", overlapping/out-of-order " + service.overlapRows() + ")");
    }

    /**
     * Runs a SQL-report import: fetch the whole report, then replace the table in one transaction (Part 1's
     * replace-all semantics). Same config, exit codes and summary as the entity imports.
     */
    private static int runSqlReport(String entity, String resource, String table, List<String> columns) {
        try {
            AppConfig config = AppConfig.load();
            String runId = RunLedger.runIdOrNew();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            SqlReportRepository repository = new SqlReportRepository(config.pgSchema(), table, columns);
            SqlReportImportService service =
                    new SqlReportImportService(client::fetchPage, resource, repository, config.pageSize());

            try (Connection conn = PostgresConnection.open(config)) {
                ImportResult result = service.run(conn, entity, runId);
                RunLedger.record(counts(repository.targetTable(), result, null));
                System.out.println();
                System.out.println(result.summary());
                System.out.println("  replaced:  " + service.lastDeleted()
                        + " previous row(s) (full snapshot, replace-all in one transaction)");
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
        System.out.println("                       Fetch GET /kfagent/entitlement-assignment (SQL report) and REPLACE <PG_DEST_SCHEMA>.kf_entitlement_assignment");
        System.out.println("  import-workgroup-member");
        System.out.println("                       Fetch GET /kfagent/workgroupmember and upsert into <PG_DEST_SCHEMA>.kf_workgroup_member");
        System.out.println("  import-entitlement-certification");
        System.out.println("                       Fetch GET /kfagent/entitlement-certification (SQL report) and REPLACE <PG_DEST_SCHEMA>.kf_entitlement_certification");
        System.out.println("  import-entitlement-certification-status");
        System.out.println("                       Fetch GET /kfagent/entitlement-certification-status (SQL report) and REPLACE <PG_DEST_SCHEMA>.kf_entitlement_certification_status");
        System.out.println("  import-workgroup-members");
        System.out.println("                       Fetch GET /kfagent/workgroup-members (SQL report) and REPLACE <PG_DEST_SCHEMA>.kf_workgroup_members");
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
        System.out.println("                       Page GET /kfagent/syslog-events (complete-scan guarded) and append into <PG_DEST_SCHEMA>.kf_syslog_event");
        System.out.println();
        System.out.println("  reconcile-native-workitems");
        System.out.println("                       Reconcile loaded approval work_item_id references against kf_workitem +");
        System.out.println("                       kf_workitem_archive; record one finding per run in <PG_DEST_SCHEMA>.kf_reconciliation_finding");
        System.out.println("  derive-native-events");
        System.out.println("                       Derive append-only events from loaded approval / task-result / provisioning-txn /");
        System.out.println("                       workitem-archive / certification-item tables into <PG_DEST_SCHEMA>.kf_event");
        System.out.println("                       (PostgreSQL only; KFAGENT_BASE_URL not required)");
        System.out.println("  derive-native-event-links");
        System.out.println("                       Derive append-only EXPLICIT links for events already in kf_event into");
        System.out.println("                       <PG_DEST_SCHEMA>.kf_event_link (PostgreSQL only; run after derive-native-events)");
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
        System.out.println("Required env: KFAGENT_BASE_URL, PG_HOST, PG_DATABASE, PG_USERNAME, PG_PASSWORD");
        System.out.println("Optional env: PG_PORT (5432), PG_DEST_SCHEMA (iiq_native_part2), KFAGENT_PAGE_SIZE (500),");
        System.out.println("              HTTP_TIMEOUT_SECONDS (60), KFAGENT_USERNAME, KFAGENT_PASSWORD");
    }
}

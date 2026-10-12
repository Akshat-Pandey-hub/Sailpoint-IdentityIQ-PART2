package com.keyforge.part2;

import com.keyforge.part2.config.AppConfig;
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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
        switch (command) {
            case "import-identity" -> System.exit(runImportIdentity());
            case "import-entitlement" -> System.exit(runImport(EntitlementTable.SPEC));
            case "import-application" -> System.exit(runImport(ApplicationTable.SPEC));
            case "import-account" -> System.exit(runImport(AccountTable.SPEC));
            case "import-workgroup" -> System.exit(runImport(WorkgroupTable.SPEC));
            case "import-role" -> System.exit(runImport(RoleTable.SPEC));
            case "import-identity-role" -> System.exit(runImport(IdentityRoleTable.SPEC));
            case "import-identity-entitlement" -> System.exit(runImport(IdentityEntitlementTable.SPEC));
            case "import-account-entitlement" -> System.exit(runImport(AccountEntitlementTable.SPEC));
            case "import-role-entitlement" -> System.exit(runImport(RoleEntitlementRepository::new));
            case "import-role-hierarchy" -> System.exit(runImport(RoleHierarchyRepository::new));
            case "import-identity-request" -> System.exit(runImport(IdentityRequestTable.SPEC));
            case "import-identity-request-item" -> System.exit(runImport(IdentityRequestItemTable.SPEC));
            case "import-identity-request-approval" -> System.exit(runImport(IdentityRequestApprovalTable.SPEC));
            case "import-policy" -> System.exit(runImport(PolicyTable.SPEC));
            case "import-provisioning-item" -> System.exit(runImport(ProvisioningItemRepository::new));
            case "import-provisioning-transaction" -> System.exit(runImport(ProvisioningTxnTable.SPEC));
            case "import-work-item" -> System.exit(runImport(WorkItemTable.SPEC));
            case "import-workflow-definition" -> System.exit(runImport(WorkflowDefinitionRepository::new));
            case "import-certification" -> System.exit(runImport(CertificationTable.SPEC));
            case "import-certification-entity" -> System.exit(runImport(CertificationEntityTable.SPEC));
            case "import-certification-item" -> System.exit(runImport(CertificationItemTable.SPEC));
            case "import-certification-archive" -> System.exit(runImport(CertificationArchiveRepository::new));
            case "import-policy-constraint" -> System.exit(runImport(PolicyConstraintTable.SPEC));
            case "import-policy-violation" -> System.exit(runImport(PolicyViolationTable.SPEC));
            case "import-entitlement-assignment" -> System.exit(runSqlReport("EntitlementAssignment",
                    EntitlementAssignmentReport.RESOURCE, EntitlementAssignmentReport.TABLE,
                    EntitlementAssignmentReport.COLUMNS));
            case "import-workgroup-member" -> System.exit(runImport(WorkgroupMemberRepository::new));
            case "import-entitlement-certification" -> System.exit(runSqlReport("EntitlementCertification",
                    EntitlementCertificationReport.RESOURCE, EntitlementCertificationReport.TABLE,
                    EntitlementCertificationReport.COLUMNS));
            case "import-entitlement-certification-status" -> System.exit(runSqlReport("EntitlementCertificationStatus",
                    EntitlementCertificationStatusReport.RESOURCE, EntitlementCertificationStatusReport.TABLE,
                    EntitlementCertificationStatusReport.COLUMNS));
            case "import-workgroup-members" -> System.exit(runSqlReport("WorkgroupMembers",
                    WorkgroupMembersReport.RESOURCE, WorkgroupMembersReport.TABLE, WorkgroupMembersReport.COLUMNS));
            case "import-audit-event" -> System.exit(runImport(AuditEventRepository::new));
            case "import-task-result" -> System.exit(runImport(TaskResultTable.SPEC));
            case "import-task-schedule" -> System.exit(runImport(TaskScheduleTable.SPEC));
            case "import-workitem-archive" -> System.exit(runImport(WorkItemArchiveRepository::new));
            case "import-group-definition" -> System.exit(runImport(GroupDefinitionTable.SPEC));
            case "import-access-history" -> System.exit(runImport(AccessHistoryEntitlementRepository::new));
            case "import-access-history-identity-event" ->
                    System.exit(runImport(AccessHistoryIdentityEventRepository::new));
            case "import-access-history-certification" ->
                    System.exit(runImport(AccessHistoryCertificationRepository::new));
            case "import-access-history-role-event" ->
                    System.exit(runImport(AccessHistoryRoleEventRepository::new));
            case "import-syslog-event" -> System.exit(runSyslogImport());
            default -> {
                printUsage();
                System.exit("".equals(command) ? 2 : 2);
            }
        }
    }

    private static int runImportIdentity() {
        try {
            AppConfig config = AppConfig.load();
            String runId = UUID.randomUUID().toString();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            IdentityRepository repository = new IdentityRepository(config.pgSchema());
            IdentityImportService service = new IdentityImportService(client, repository, config.pageSize());

            try (Connection conn = PostgresConnection.open(config)) {
                ImportResult result = service.run(conn, runId);
                System.out.println();
                System.out.println(result.summary());
                return result.failed() > 0 ? 6 : 0;
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            return 3;
        } catch (KfAgentClient.KfAgentHttpException e) {
            System.err.println("KF Agent HTTP/source error: " + e.getMessage());
            return 4;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
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
            String runId = UUID.randomUUID().toString();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            EntityWriter writer = writerForSchema.apply(config.pgSchema());
            Optional<SweepPolicies.SweepConfig> sweep = SweepPolicies.configFor(writer.targetTable());

            try (Connection conn = PostgresConnection.open(config)) {
                if (sweep.isEmpty()) {
                    // not swept: the ordinary paged import, unchanged
                    ImportResult result = new EntityImportService(client, writer, config.pageSize()).run(conn, runId);
                    System.out.println();
                    System.out.println(result.summary());
                    return result.failed() > 0 ? 6 : 0;
                }
                SnapshotSweepImportService.Outcome outcome = new SnapshotSweepImportService(client::fetchSnapshot,
                        writer, sweep.get(), new SoftDeleteSweeper(config.pgSchema()), false).run(conn, runId);
                System.out.println();
                System.out.println(outcome.result().summary());
                return printSweep(outcome.sweep(), outcome.result().failed());
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            return 3;
        } catch (KfAgentClient.KfAgentHttpException e) {
            System.err.println("KF Agent HTTP/source error: " + e.getMessage());
            return 4;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
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
            String runId = UUID.randomUUID().toString();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            SyslogEventImportService service = new SyslogEventImportService(
                    client::fetch, new SyslogEventRepository(config.pgSchema()), config.pageSize());

            try (Connection conn = PostgresConnection.open(config)) {
                try {
                    ImportResult result = service.run(conn, runId);
                    System.out.println();
                    System.out.println(result.summary());
                    printSyslogScan(service, "complete");
                    return result.failed() > 0 ? 6 : 0;
                } catch (SyslogEventImportService.IncompleteScanException e) {
                    System.out.println();
                    System.out.println(e.partial().summary());
                    printSyslogScan(service, "NOT CONFIRMED COMPLETE");
                    System.err.println("Incomplete scan: " + e.getMessage());
                    return 7;
                }
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            return 3;
        } catch (KfAgentClient.KfAgentHttpException e) {
            System.err.println("KF Agent HTTP/source error: " + e.getMessage());
            return 4;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
            return 1;
        }
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
            return 7;
        }
        return failedRows > 0 ? 6 : 0;
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
            String runId = UUID.randomUUID().toString();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            SqlReportRepository repository = new SqlReportRepository(config.pgSchema(), table, columns);
            SqlReportImportService service =
                    new SqlReportImportService(client::fetchPage, resource, repository, config.pageSize());

            try (Connection conn = PostgresConnection.open(config)) {
                ImportResult result = service.run(conn, entity, runId);
                System.out.println();
                System.out.println(result.summary());
                System.out.println("  replaced:  " + service.lastDeleted()
                        + " previous row(s) (full snapshot, replace-all in one transaction)");
                return result.failed() > 0 ? 6 : 0;
            }
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            return 3;
        } catch (KfAgentClient.KfAgentHttpException e) {
            System.err.println("KF Agent HTTP/source error: " + e.getMessage());
            return 4;
        } catch (SQLException e) {
            System.err.println("PostgreSQL error: " + e.getMessage());
            return 5;
        } catch (RuntimeException e) {
            System.err.println("Unexpected error: " + e.getMessage());
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
        System.out.println("Required env: KFAGENT_BASE_URL, PG_HOST, PG_DATABASE, PG_USERNAME, PG_PASSWORD");
        System.out.println("Optional env: PG_PORT (5432), PG_DEST_SCHEMA (iiq_native_part2), KFAGENT_PAGE_SIZE (500),");
        System.out.println("              HTTP_TIMEOUT_SECONDS (60), KFAGENT_USERNAME, KFAGENT_PASSWORD");
    }
}

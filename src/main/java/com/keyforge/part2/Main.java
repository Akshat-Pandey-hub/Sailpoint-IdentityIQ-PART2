package com.keyforge.part2;

import com.keyforge.part2.config.AppConfig;
import com.keyforge.part2.config.ConfigException;
import com.keyforge.part2.db.PostgresConnection;
import com.keyforge.part2.http.KfAgentClient;
import com.keyforge.part2.ingest.AccountTable;
import com.keyforge.part2.ingest.ApplicationTable;
import com.keyforge.part2.ingest.EntitlementTable;
import com.keyforge.part2.ingest.EntityImportService;
import com.keyforge.part2.ingest.EntityRepository;
import com.keyforge.part2.ingest.EntitySpec;
import com.keyforge.part2.ingest.IdentityImportService;
import com.keyforge.part2.ingest.IdentityRepository;
import com.keyforge.part2.ingest.IdentityEntitlementTable;
import com.keyforge.part2.ingest.IdentityRoleTable;
import com.keyforge.part2.ingest.RoleTable;
import com.keyforge.part2.ingest.WorkgroupTable;
import com.keyforge.part2.ingest.ImportResult;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

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
 *                       Fetch GET /kfagent/identity-entitlements and upsert into &lt;PG_DEST_SCHEMA&gt;.kf_identity_entitlement</pre>
 *
 * Configuration is entirely external (environment variables / -D system properties) — see {@link AppConfig}.
 * Exit codes: 0 ok, 2 usage, 3 config error, 4 HTTP/source error, 5 PostgreSQL error, 6 completed with row
 * failures, 1 unexpected.
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
        try {
            AppConfig config = AppConfig.load();
            String runId = UUID.randomUUID().toString();
            System.out.println(config.describe());
            System.out.println("extraction_run_id: " + runId);

            KfAgentClient client = new KfAgentClient(config);
            EntityRepository repository = new EntityRepository(config.pgSchema(), spec);
            EntityImportService service = new EntityImportService(client, repository, config.pageSize());

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
        System.out.println();
        System.out.println("Required env: KFAGENT_BASE_URL, PG_HOST, PG_DATABASE, PG_USERNAME, PG_PASSWORD");
        System.out.println("Optional env: PG_PORT (5432), PG_DEST_SCHEMA (iiq_native_part2), KFAGENT_PAGE_SIZE (500),");
        System.out.println("              HTTP_TIMEOUT_SECONDS (60), KFAGENT_USERNAME, KFAGENT_PASSWORD");
    }
}

package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enabled sweep configuration matches the real writers: exactly the Part 1-swept tables with a verified source
 * count, the right failure policy, each configured PK equal to the writer's PK, and each writer's {@code key()} equal to
 * the PK Part 1 stores (golden fixtures). Everything else stays NEVER.
 */
class SweepPoliciesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<EntitySpec> SPECS = List.of(
            IdentityEntitlementTable.SPEC, CertificationTable.SPEC, CertificationEntityTable.SPEC,
            CertificationItemTable.SPEC, WorkItemTable.SPEC, IdentityRequestTable.SPEC, ProvisioningTxnTable.SPEC,
            IdentityRequestItemTable.SPEC, IdentityRequestApprovalTable.SPEC, IdentityRoleTable.SPEC,
            AccountEntitlementTable.SPEC, PolicyConstraintTable.SPEC);

    @Test
    void exactlyTheVerifiedPart1SweptTablesAreEnabledWithTheirPart1Policy() {
        assertEquals(Set.of("kf_identity_entitlement", "kf_certification", "kf_certification_entity",
                "kf_certification_item", "kf_workitem", "kf_identity_request", "kf_provisioning_txn",
                "kf_identity_request_item", "kf_identity_request_approval", "kf_provisioning_item", "kf_identity_role",
                "kf_account_entitlement", "kf_policy_constraint", "kf_role_entitlement", "kf_role_hierarchy"),
                SweepPolicies.CONFIGS.keySet());
        for (Map.Entry<String, SweepPolicies.SweepConfig> e : SweepPolicies.CONFIGS.entrySet()) {
            assertFalse(SweepPolicies.isNeverSweepTable(e.getKey()));
            SweepPolicy expected = e.getKey().startsWith("kf_role_")
                    ? SweepPolicy.SWEEP_UNLESS_ANY_FAILURE : SweepPolicy.SWEEP_PROTECT_FAILED_KEYS;
            assertEquals(expected, e.getValue().policy(), e.getKey());
        }
    }

    @Test
    void tablesWithoutAProvableCountStayNever() {
        for (String table : List.of("kf_identity", "kf_account", "kf_application", "kf_entitlement", "kf_role",
                "kf_policy", "kf_task_result", "kf_task_schedule", "kf_violation", "kf_workgroup",
                "kf_group_definition", "kf_workflow_definition", "kf_workgroup_member")) {
            assertEquals(SweepPolicy.NEVER, SweepPolicies.policyFor(table), table);
        }
    }

    @Test
    void configuredPrimaryKeysMatchTheWriters() {
        for (EntitySpec spec : SPECS) {
            SweepPolicies.SweepConfig c = SweepPolicies.configFor(spec.table()).orElseThrow();
            assertEquals(spec.pkColumn(), c.pkColumn(), spec.table());
            assertTrue(new EntityRepository("s", spec).createTableSql().contains(c.pkColumn() + " uuid PRIMARY KEY"));
        }
        assertPk(new RoleEntitlementRepository("s").createTableSql(), "kf_role_entitlement");
        assertPk(new RoleHierarchyRepository("s").createTableSql(), "kf_role_hierarchy");
        assertPk(new ProvisioningItemRepository("s").createTableSql(), "kf_provisioning_item");
        assertEquals("kf_identity_role", SweepPolicies.configFor("iiq_native_part2.kf_identity_role").orElseThrow()
                .table(), "a schema-qualified writer table resolves to its config");
    }

    private static void assertPk(String ddl, String table) {
        String pk = SweepPolicies.configFor(table).orElseThrow().pkColumn();
        assertTrue(ddl.contains(pk + " uuid PRIMARY KEY"), table + " PK " + pk);
    }

    @Test
    void writerKeysEqualThePrimaryKeysPart1Stores() throws Exception {
        assertGoldenKeys(new RoleEntitlementRepository("s"), "/role-entitlement-part1-golden.txt");
        assertGoldenKeys(new RoleHierarchyRepository("s"), "/role-hierarchy-part1-golden.txt");
        assertGoldenKeys(new ProvisioningItemRepository("s"), "/provisioning-item-part1-golden.txt");
        assertGoldenKeys(new EntityRepository("s", IdentityEntitlementTable.SPEC), "/identity-entitlement-part1-golden.txt");
        assertGoldenKeys(new EntityRepository("s", IdentityRoleTable.SPEC), "/identity-role-part1-golden.txt");
    }

    /** Every {@code ID=} in a golden fixture is the PK Part 1 stored for the preceding {@code REST=} row. */
    private static void assertGoldenKeys(EntityWriter writer, String resource) throws Exception {
        int checked = 0;
        try (InputStream in = SweepPoliciesTest.class.getResourceAsStream(resource);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) {
                    rest = line.substring(5);
                } else if (line.startsWith("ID=") && rest != null && !line.substring(3).equals("THROWS")) {
                    assertEquals(line.substring(3), writer.key(MAPPER.readTree(rest)), resource);
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "fixture " + resource + " has keyed rows");
    }
}

package com.keyforge.part2.ingest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The sweep policy registry. A table sweeps only if it is listed in {@link #CONFIGS}; every other table is
 * {@link SweepPolicy#NEVER}. Tables in {@link #NEVER_SWEEP_TABLES} can never be given another policy
 * ({@link SoftDeleteSweeper.Target} rejects it): append-only events / histories / archives are immutable, SQL-report
 * tables mark their own deleted rows ({@link SqlReportImportService}), and governance-owned tables are not source mirrors.
 *
 * <p>Enabled = exactly the tables Part 1 sweeps whose KF Agent endpoint reports a VERIFIED source count
 * ({@code X-KF-Verified-Source-Count}: the response came from one full internal scan whose complete-scan guard passed).
 * Tables Part 1 sweeps but whose source offers no count (identity, account, application, entitlement, role, policy,
 * task result/schedule, violation, workgroup, group definition, workflow definition, workgroup member) stay NEVER:
 * their completeness cannot be proven.
 */
public final class SweepPolicies {

    private SweepPolicies() {
    }

    /** How a verified snapshot proves the response covers the whole population. */
    public enum Completeness {
        /** Each row is one counted IIQ object: the row count must equal the verified source count. */
        ROW_COUNT,
        /**
         * Rows are edges / children derived from a parent scan whose count was verified (the agent's guard: every
         * parent scanned); the row count is not comparable to the parent count.
         */
        PARENT_SCAN
    }

    /** An enabled table: its primary key, Part 1 failure policy and completeness rule. */
    public record SweepConfig(String table, String pkColumn, SweepPolicy policy, Completeness completeness) {
        public SoftDeleteSweeper.Target target() {
            return new SoftDeleteSweeper.Target(table, pkColumn, policy);
        }
    }

    /** Tables that must never be swept, whatever an entity configures. */
    public static final Set<String> NEVER_SWEEP_TABLES = Set.of(
            // append-only events, histories and archives
            "kf_audit_event", "kf_syslog_event", "kf_certification_archive", "kf_workitem_archive",
            "kf_access_hist_entitlement", "kf_access_hist_identity_event", "kf_access_hist_role_event",
            "kf_access_hist_certification",
            // SQL-report tables (their own full run marks rows the report no longer returns; see SqlReportImportService)
            "kf_entitlement_assignment", "kf_workgroup_members", "kf_entitlement_certification",
            "kf_entitlement_certification_status",
            // governance-owned tables
            "kf_event", "kf_event_link", "kf_extraction_run", "kf_extraction_watermark", "kf_reconciliation_finding");

    /** Enabled tables (Part 1 guard group in brackets). */
    public static final Map<String, SweepConfig> CONFIGS;

    static {
        Map<String, SweepConfig> m = new LinkedHashMap<>();
        // [B] import throws unless extracted == IIQ countObjects
        put(m, "kf_identity_entitlement", "entitlementid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS, Completeness.ROW_COUNT);
        put(m, "kf_certification", "certificationid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS, Completeness.ROW_COUNT);
        put(m, "kf_certification_entity", "certificationentityid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS,
                Completeness.ROW_COUNT);
        put(m, "kf_certification_item", "certificationitemid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS,
                Completeness.ROW_COUNT);
        put(m, "kf_workitem", "workitemid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS, Completeness.ROW_COUNT);
        put(m, "kf_identity_request", "identityrequestid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS, Completeness.ROW_COUNT);
        put(m, "kf_provisioning_txn", "provisioningtxnid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS, Completeness.ROW_COUNT);
        // [B, children of the verified request / transaction scan]
        put(m, "kf_identity_request_item", "identityrequestitemid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS,
                Completeness.PARENT_SCAN);
        put(m, "kf_identity_request_approval", "identityrequestapprovalid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS,
                Completeness.PARENT_SCAN);
        put(m, "kf_provisioning_item", "provisioningitemid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS,
                Completeness.PARENT_SCAN);
        // [C] edges: import throws unless every parent (Identity / Link / Policy) was scanned
        put(m, "kf_identity_role", "identityroleid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS, Completeness.PARENT_SCAN);
        put(m, "kf_account_entitlement", "accountentitlementid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS,
                Completeness.PARENT_SCAN);
        put(m, "kf_policy_constraint", "policyconstraintid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS,
                Completeness.PARENT_SCAN);
        // [D] Bundle-count-verified role relationships: Part 1 skips the sweep after ANY row failure
        put(m, "kf_role_entitlement", "roleentitlementid", SweepPolicy.SWEEP_UNLESS_ANY_FAILURE, Completeness.PARENT_SCAN);
        put(m, "kf_role_hierarchy", "rolehierarchyid", SweepPolicy.SWEEP_UNLESS_ANY_FAILURE, Completeness.PARENT_SCAN);
        CONFIGS = Map.copyOf(m);
    }

    private static void put(Map<String, SweepConfig> m, String table, String pk, SweepPolicy p, Completeness c) {
        SweepConfig config = new SweepConfig(table, pk, p, c);
        config.target(); // validates identifiers and rejects never-sweep tables at class load
        m.put(table, config);
    }

    /** The enabled sweep for an unqualified (or schema-qualified) table name, if any. */
    public static Optional<SweepConfig> configFor(String table) {
        if (table == null) {
            return Optional.empty();
        }
        String unqualified = table.substring(table.lastIndexOf('.') + 1);
        return Optional.ofNullable(CONFIGS.get(unqualified));
    }

    /** The policy for a table: its enabled config's, else {@link SweepPolicy#NEVER}. */
    public static SweepPolicy policyFor(String table) {
        return configFor(table).map(SweepConfig::policy).orElse(SweepPolicy.NEVER);
    }

    public static boolean isNeverSweepTable(String table) {
        return table != null && NEVER_SWEEP_TABLES.contains(table);
    }
}

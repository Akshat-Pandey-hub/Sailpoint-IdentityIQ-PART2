package com.keyforge.part2.ingest;

import java.util.Set;

/**
 * The sweep policy registry. Nothing is enabled yet: {@link #policyFor} returns {@link SweepPolicy#NEVER} for every
 * table. Tables in {@link #NEVER_SWEEP_TABLES} can never be given another policy ({@link SoftDeleteSweeper.Target}
 * rejects it): append-only events / histories / archives are immutable, SQL-report tables are replaced as a whole, and
 * governance-owned tables are not source mirrors.
 */
public final class SweepPolicies {

    private SweepPolicies() {
    }

    /** Tables that must never be swept, whatever an entity configures. */
    public static final Set<String> NEVER_SWEEP_TABLES = Set.of(
            // append-only events, histories and archives
            "kf_audit_event", "kf_syslog_event", "kf_certification_archive", "kf_workitem_archive",
            "kf_access_hist_entitlement", "kf_access_hist_identity_event", "kf_access_hist_role_event",
            "kf_access_hist_certification",
            // SQL-report tables (replace-all snapshots)
            "kf_entitlement_assignment", "kf_workgroup_members", "kf_entitlement_certification",
            "kf_entitlement_certification_status",
            // governance-owned tables
            "kf_event", "kf_event_link", "kf_extraction_run", "kf_extraction_watermark", "kf_reconciliation_finding");

    /** The policy configured for an unqualified table name: {@link SweepPolicy#NEVER} for all tables for now. */
    public static SweepPolicy policyFor(String table) {
        return SweepPolicy.NEVER;
    }

    public static boolean isNeverSweepTable(String table) {
        return table != null && NEVER_SWEEP_TABLES.contains(table);
    }
}

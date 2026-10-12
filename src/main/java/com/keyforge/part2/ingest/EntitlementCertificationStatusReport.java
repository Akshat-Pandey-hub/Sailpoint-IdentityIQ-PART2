package com.keyforge.part2.ingest;

import java.util.List;

/**
 * {@code GET /kfagent/entitlement-certification-status} → {@code kf_entitlement_certification_status}: Part 1's
 * SQL-report "Query 4" (registered report id {@code entitlementCertificationStatus}), one row per IIQ identity
 * entitlement plus its latest certification. Stored as the query result, exactly as Part 1's
 * {@code ENTITLEMENT_CERTIFICATION_STATUS_COLUMNS}: 14 text columns in SELECT-alias order, replace-all per run (same
 * {@link SqlReportRepository} contract as {@link EntitlementAssignmentReport}).
 *
 * <p>Relationships are values inside each row, not keys: {@code user_id} (identity name) / {@code manager_user_id}
 * (manager name) / {@code application_name} / {@code account_name} (native identity) / {@code entitlement_attribute} /
 * {@code entitlement_value} identify the identity entitlement. The application join is a LEFT JOIN, so role rows
 * ({@code assignedRoles}) have NULL application and account. The certification columns come from the latest
 * certification item (by action decision date DESC NULLS LAST, then certification created DESC) whose entity targets
 * the identity id and whose exception application / attribute / value match, with the entity's native identity equal
 * to the account or NULL: {@code last_certification_name} = certification name, {@code last_certified_date} = action
 * decision date, {@code certifier} = action actor name, {@code certification_decision} = action status, and
 * {@code never_certified} = 'YES' when no such item exists. Dates are text as IIQ's
 * {@code to_timestamp(epoch-ms / 1000.0)} renders them. The row's {@code id} is a database row number only.
 */
public final class EntitlementCertificationStatusReport {

    private EntitlementCertificationStatusReport() {
    }

    public static final String RESOURCE = "entitlement-certification-status";
    public static final String TABLE = "kf_entitlement_certification_status";

    /** Part 1 {@code Main.ENTITLEMENT_CERTIFICATION_STATUS_COLUMNS}: the query's SELECT aliases, in order. */
    public static final List<String> COLUMNS = List.of(
            "user_id", "user_name", "manager_user_id", "manager_name", "application_name", "account_name",
            "entitlement_attribute", "entitlement_value", "entitlement_assigned_date",
            "last_certification_name", "last_certified_date", "certifier", "certification_decision",
            "never_certified");
}

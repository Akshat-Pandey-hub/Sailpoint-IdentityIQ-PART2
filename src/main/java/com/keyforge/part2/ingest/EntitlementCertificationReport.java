package com.keyforge.part2.ingest;

import java.util.List;

/**
 * {@code GET /kfagent/entitlement-certification} → {@code kf_entitlement_certification}: Part 1's SQL-report "Query 3"
 * (registered report id {@code entitlementCertification}), one row per IIQ identity entitlement plus a
 * {@code never_certified} flag ('YES' / 'NO'). Stored as the query result, exactly as Part 1's
 * {@code ENTITLEMENT_CERTIFICATION_COLUMNS}: 9 text columns in SELECT-alias order, replace-all per run (same
 * {@link SqlReportRepository} contract as {@link EntitlementAssignmentReport}).
 *
 * <p>Relationships are values inside each row, not keys: {@code user_id} (identity name) / {@code application_name} /
 * {@code account_name} (native identity) / {@code entitlement_attribute} / {@code entitlement_value} identify the
 * identity entitlement and its catalog entitlement; {@code never_certified} is 'NO' when a certification item with
 * matching exception application / attribute / value exists on a certification entity whose target is that identity
 * (by id or name) and whose native identity equals the account or is NULL. The dates are text exactly as IIQ's
 * {@code to_timestamp(epoch-ms / 1000.0)} renders them (e.g. {@code 2025-07-27 03:27:26.258+00}). The row's {@code id}
 * is a database row number only.
 */
public final class EntitlementCertificationReport {

    private EntitlementCertificationReport() {
    }

    public static final String RESOURCE = "entitlement-certification";
    public static final String TABLE = "kf_entitlement_certification";

    /** Part 1 {@code Main.ENTITLEMENT_CERTIFICATION_COLUMNS}: the query's SELECT aliases, in order. */
    public static final List<String> COLUMNS = List.of(
            "user_id", "user_name", "application_name", "account_name", "entitlement_attribute",
            "entitlement_value", "entitlement_assigned_date", "entitlement_modified_date", "never_certified");
}

package com.keyforge.part2.ingest;

import java.util.List;

/**
 * {@code GET /kfagent/entitlement-assignment} → {@code kf_entitlement_assignment}: Part 1's SQL-report "Query 1"
 * (registered report id {@code entitlementAssignment}), which joins IIQ identities, accounts, entitlements, roles and
 * access requests into one row per (identity, entitlement, provenance). Stored as the query result, exactly as Part 1's
 * {@code NativeSqlReportRepository.COLUMNS}: 29 text columns in SELECT-alias order, stored keyed by {@link #KEY_COLUMNS}
 * (see {@link SqlReportRepository}).
 *
 * <p>Relationships are values inside each row (raw IIQ ids / names as text), not keys: {@code identity_id} /
 * {@code user_id} / {@code user_name} (the identity), {@code application_name} / {@code account_name} (the account),
 * {@code entitlement_attribute} / {@code entitlement_value} (the entitlement), {@code entitlement_assignment_id},
 * {@code role_id} / {@code role_name} (granting role), {@code identity_request_id} / {@code access_request_id} (the
 * request). The row's uuid {@code entitlementassignmentid} is derived from {@link #KEY_COLUMNS}.
 */
public final class EntitlementAssignmentReport {

    private EntitlementAssignmentReport() {
    }

    public static final String RESOURCE = "entitlement-assignment";
    public static final String TABLE = "kf_entitlement_assignment";

    /** Part 1 {@code NativeSqlReportRepository.COLUMNS}: the query's SELECT aliases, in order. */
    public static final List<String> COLUMNS = List.of(
            "identity_id", "user_id", "user_name", "application_name", "account_name",
            "entitlement_attribute", "entitlement_value", "assigned", "granted_by_role",
            "entitlement_source", "entitlement_assignment_id", "entitlement_assigner",
            "entitlement_created", "entitlement_modified", "role_id", "role_name", "role_display_name",
            "role_type", "role_requestable", "identity_request_id", "access_request_id", "request_type",
            "requested_by", "request_date", "request_status", "request_operation", "provisioning_state",
            "assignment_type", "assignment_reason");

    public static final String PK = "entitlementassignmentid";

    /** The columns that identify one report row (unique in the report result); the row uuid is derived from them. */
    public static final List<String> KEY_COLUMNS = List.of(
            "identity_id", "application_name", "account_name", "entitlement_attribute",
            "entitlement_value", "role_id", "identity_request_id", "request_operation");

    public static final SqlReportSpec SPEC = new SqlReportSpec("EntitlementAssignment", "entitlementAssignment",
            RESOURCE, TABLE, PK, COLUMNS, KEY_COLUMNS);
}

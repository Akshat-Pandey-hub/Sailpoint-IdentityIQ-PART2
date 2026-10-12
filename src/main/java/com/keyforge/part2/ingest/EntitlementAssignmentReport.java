package com.keyforge.part2.ingest;

import java.util.List;

/**
 * {@code GET /kfagent/entitlement-assignment} → {@code kf_entitlement_assignment}: Part 1's SQL-report "Query 1"
 * (registered report id {@code entitlementAssignment}), which joins IIQ identities, accounts, entitlements, roles and
 * access requests into one row per (identity, entitlement, provenance). Stored as the query result, exactly as Part 1's
 * {@code NativeSqlReportRepository.COLUMNS}: 29 text columns in SELECT-alias order, replace-all per run.
 *
 * <p>Relationships are values inside each row (raw IIQ ids / names as text), not keys: {@code identity_id} /
 * {@code user_id} / {@code user_name} (the identity), {@code application_name} / {@code account_name} (the account),
 * {@code entitlement_attribute} / {@code entitlement_value} (the entitlement), {@code entitlement_assignment_id},
 * {@code role_id} / {@code role_name} (granting role), {@code identity_request_id} / {@code access_request_id} (the
 * request). The row's {@code id} is a database row number only.
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
}

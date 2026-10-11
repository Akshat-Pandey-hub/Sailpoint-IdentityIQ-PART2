package com.keyforge.part2.ingest;

import java.util.List;

/**
 * The {@code kf_identity} destination contract, taken verbatim from Part 1's schema (iiq_native.sql /
 * NativeIdentityRepository). The BUSINESS columns are exactly the fields the {@code GET /kfagent/identities}
 * response provides, under the same names — so mapping is 1:1 (no renames). The primary key
 * {@code userid uuid} and the 7 source-metadata columns are NOT in the response; Part 2 generates them.
 * {@code kf_identity} intentionally has NO {@code record_hash} column.
 */
public final class IdentityTable {

    private IdentityTable() {
    }

    public static final String TABLE = "kf_identity";
    public static final String PK = "userid"; // uuid

    // ── source-metadata constant values (Part 1 rules) ──
    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Identity";

    /** Business columns in schema order; names match the KF Agent response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("display_name"),
            ColumnSpec.text("displayable_name"),
            ColumnSpec.text("first_name"),
            ColumnSpec.text("last_name"),
            ColumnSpec.text("email"),
            ColumnSpec.bool("inactive"),
            ColumnSpec.text("type"),
            ColumnSpec.bool("correlated"),
            ColumnSpec.bool("manager_status"),
            ColumnSpec.text("manager_id"),
            ColumnSpec.text("manager_name"),
            ColumnSpec.text("administrator_id"),
            ColumnSpec.text("administrator_name"),
            ColumnSpec.jsonb("accounts"),
            ColumnSpec.jsonb("assigned_roles"),
            ColumnSpec.jsonb("detected_roles"),
            ColumnSpec.jsonb("role_assignments"),
            ColumnSpec.jsonb("role_detections"),
            ColumnSpec.jsonb("role_requests"),
            ColumnSpec.jsonb("mitigation_expirations"),
            ColumnSpec.jsonb("capabilities"),
            ColumnSpec.jsonb("controlled_scopes"),
            ColumnSpec.jsonb("attributes"),
            ColumnSpec.text("score"),
            ColumnSpec.text("full_name"),
            ColumnSpec.bool("is_protected"),
            ColumnSpec.bool("is_needs_refresh"),
            ColumnSpec.bool("is_correlated_overridden"),
            ColumnSpec.bool("is_workgroup"),
            ColumnSpec.text("auth_application"),
            ColumnSpec.text("auth_account"),
            ColumnSpec.text("pending_refresh_workflow"),
            ColumnSpec.timestamptz("password_expiration"),
            ColumnSpec.timestamptz("auth_lock_start"),
            ColumnSpec.timestamptz("use_by"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"),
            ColumnSpec.timestamptz("last_refresh"),
            ColumnSpec.timestamptz("last_login"));
}

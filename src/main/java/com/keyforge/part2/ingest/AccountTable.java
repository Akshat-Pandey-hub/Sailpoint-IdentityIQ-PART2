package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/accounts} → {@code kf_account} (IIQ {@code Link}), taken from Part 1's iiq_native.sql +
 * {@code NativeLinkRepository} / {@code NativeLinkRow}:
 * <ul>
 *   <li>PK {@code accountid uuid} = {@link CanonicalIds#accountId} (not in the response); the fallback seed is
 *       the account's natural key {@code application_name|native_identity}</li>
 *   <li>29 business columns = the 29 response fields, same names, table order (5 {@code jsonb},
 *       7 booleans, 6 timestamps; {@code component_ids} is text)</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 29 business fields (Part 1's {@code recordHash})</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.Link}</li>
 * </ul>
 * {@code identity_id} / {@code application_id} are stored as raw IIQ ids (text), exactly as in Part 1 — no
 * foreign keys exist on kf_account, so accounts load independently of identity/application imports.
 */
public final class AccountTable {

    private AccountTable() {
    }

    public static final String RESOURCE = "accounts";
    public static final String TABLE = "kf_account";
    public static final String PK = "accountid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.Link";

    /** Business columns in iiq_native.kf_account order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("link_uuid"),
            ColumnSpec.text("native_identity"),
            ColumnSpec.text("display_name"),
            ColumnSpec.text("displayable_name"),
            ColumnSpec.text("instance"),
            ColumnSpec.text("component_ids"),
            ColumnSpec.text("application_id"),
            ColumnSpec.text("application_name"),
            ColumnSpec.text("identity_id"),
            ColumnSpec.text("identity_name"),
            ColumnSpec.bool("disabled"),
            ColumnSpec.bool("locked"),
            ColumnSpec.bool("composite"),
            ColumnSpec.bool("manually_correlated"),
            ColumnSpec.bool("has_entitlements"),
            ColumnSpec.bool("iiq_disabled"),
            ColumnSpec.bool("iiq_locked"),
            ColumnSpec.jsonb("permissions"),
            ColumnSpec.jsonb("target_permissions"),
            ColumnSpec.jsonb("attributes"),
            ColumnSpec.jsonb("entitlement_attributes"),
            ColumnSpec.jsonb("attribute_metadata"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"),
            ColumnSpec.timestamptz("last_refresh"),
            ColumnSpec.timestamptz("last_target_aggregation"),
            ColumnSpec.timestamptz("significant_modified"),
            ColumnSpec.timestamptz("prior_significant_modified"));

    public static final EntitySpec SPEC = new EntitySpec(
            "Account", RESOURCE, TABLE, PK, BUSINESS,
            AccountTable::accountId,
            AccountTable::recordHash,
            AccountTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String accountId(JsonNode row) {
        return CanonicalIds.accountId(text(row, "source_id"), text(row, "application_name"),
                text(row, "native_identity"));
    }

    /** Part 1 {@code NativeLinkRepository.recordHash}: every business column, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, BUSINESS);
    }

    private static String ref(JsonNode row) {
        String id = text(row, "source_id");
        if (id != null) {
            return id;
        }
        return text(row, "application_name") + "/" + text(row, "native_identity");
    }
}

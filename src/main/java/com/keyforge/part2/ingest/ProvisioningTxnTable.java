package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.id.CanonicalIds;

import java.util.List;

import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/provisioning-transactions} → {@code kf_provisioning_txn} (IIQ {@code ProvisioningTransaction}),
 * taken from Part 1's iiq_native.sql + {@code NativeProvisioningTxnRepository} / {@code NativeProvisioningTxnRow}:
 * <ul>
 *   <li>PK {@code provisioningtxnid uuid} = {@link CanonicalIds#provisioningTxnId}: canonical source_id; a non-GUID id
 *       uses {@code deterministicUuid("native-provisioning-txn|"+id)}; a null/blank id fails the row. These rules
 *       differ from the items' (whose key is always name-based over the item content).</li>
 *   <li>Link to the plan's items: {@code kf_provisioning_item.txn_source_id} (= its {@code src_object_id}) holds this
 *       transaction's RAW {@code source_id}, not the uuid {@code provisioningtxnid}; join on
 *       {@code kf_provisioning_item.txn_source_id = kf_provisioning_txn.source_id}. {@code item_count} is the number of
 *       items Part 1 derived from the transaction's plan. The plan itself is not a column: its outcome is
 *       {@code plan_result_status} / {@code plan_result_request_id} / {@code plan_result_errors} and
 *       {@code account_request_operation}.</li>
 *   <li>34 business columns = the 34 response fields, same names, table order: {@code plan_result_errors} is the only
 *       {@code jsonb}; {@code retry_count} / {@code item_count} integer; {@code forced} / {@code timed_out} /
 *       {@code filtered} boolean; 3 timestamps; all else text.</li>
 *   <li>{@code record_hash} = SHA-256 over ALL 34 fields (Part 1's {@code recordHash}).</li>
 *   <li>lineage: {@code IdentityIQ} / {@code native_iiq_java_api} / {@code sailpoint.object.ProvisioningTransaction}</li>
 * </ul>
 */
public final class ProvisioningTxnTable {

    private ProvisioningTxnTable() {
    }

    public static final String RESOURCE = "provisioning-transactions";
    public static final String TABLE = "kf_provisioning_txn";
    public static final String PK = "provisioningtxnid";

    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String SOURCE_OBJECT_TYPE = "sailpoint.object.ProvisioningTransaction";

    /** Business columns in iiq_native.kf_provisioning_txn order; names match the response keys exactly. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("source_id"),
            ColumnSpec.text("name"),
            ColumnSpec.text("operation"),
            ColumnSpec.text("type"),
            ColumnSpec.text("status"),
            ColumnSpec.text("source"),
            ColumnSpec.text("integration"),
            ColumnSpec.bool("forced"),
            ColumnSpec.text("identity_name"),
            ColumnSpec.text("identity_display_name"),
            ColumnSpec.text("application_name"),
            ColumnSpec.text("native_identity"),
            ColumnSpec.text("account_display_name"),
            ColumnSpec.text("certification_id"),
            ColumnSpec.text("certification_name"),
            ColumnSpec.text("access_request_id"),
            ColumnSpec.text("wait_work_item_id"),
            ColumnSpec.text("manual_work_item_id"),
            ColumnSpec.text("ticket_id"),
            ColumnSpec.integer("retry_count"),
            ColumnSpec.bool("timed_out"),
            ColumnSpec.bool("filtered"),
            ColumnSpec.text("retry_request_id"),
            ColumnSpec.timestamptz("last_retry"),
            ColumnSpec.text("plan_result_status"),
            ColumnSpec.text("plan_result_request_id"),
            ColumnSpec.jsonb("plan_result_errors"),
            ColumnSpec.text("account_request_operation"),
            ColumnSpec.text("request_id"),
            ColumnSpec.integer("item_count"),
            ColumnSpec.text("owner_id"),
            ColumnSpec.text("owner_name"),
            ColumnSpec.timestamptz("created_at"),
            ColumnSpec.timestamptz("modified_at"));

    public static final EntitySpec SPEC = new EntitySpec(
            "ProvisioningTransaction", RESOURCE, TABLE, PK, BUSINESS,
            ProvisioningTxnTable::provisioningTxnId,
            ProvisioningTxnTable::recordHash,
            ProvisioningTxnTable::ref,
            SOURCE_SYSTEM, SOURCE_INTERFACE, SOURCE_OBJECT_TYPE);

    public static String provisioningTxnId(JsonNode row) {
        // no IIQ id: a uuid from the full content, never a shared substitute or a refused row
        return CanonicalIds.bySourceIdOrContent(row, "native-provisioning-txn", () -> CanonicalIds.provisioningTxnId(text(row, "source_id")));
    }

    /** Part 1 {@code NativeProvisioningTxnRepository.recordHash}: every business column, lineage excluded. */
    public static String recordHash(JsonNode r) {
        return BusinessHash.allColumns(r, BUSINESS);
    }

    private static String ref(JsonNode row) {
        return "txn " + text(row, "name") + " (source_id=" + text(row, "source_id") + ", " + text(row, "operation")
                + " " + text(row, "application_name") + "/" + text(row, "native_identity") + ")";
    }
}

package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.config.SchemaName;
import com.keyforge.part2.hash.RecordHash;
import com.keyforge.part2.id.CanonicalIds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.hash.HashValues.bool;
import static com.keyforge.part2.hash.HashValues.integer;
import static com.keyforge.part2.hash.HashValues.json;
import static com.keyforge.part2.hash.HashValues.text;

/**
 * {@code GET /kfagent/provisioning-items} → {@code kf_provisioning_item}: the AttributeRequest / PermissionRequest
 * items DERIVED from a ProvisioningTransaction's plan, reproducing Part 1's {@code NativeProvisioningItemRepository} +
 * {@code NativeProvisioningTxnParser.toItem}. A dedicated {@link EntityWriter} because Part 1 persists two derived
 * columns the response omits, placed between the lineage columns:
 * <ul>
 *   <li>Items have NO IIQ id of their own. PK {@code provisioningitemid} =
 *       {@code deterministicUuid("native-provisioning-item|" + txn_source_id|item_type|operation|name|value_json|value|item_index)}
 *       (always name-based; a null part becomes the text "null"). It is NOT the transaction id.</li>
 *   <li>Parent link: {@code txn_source_id} = the raw IIQ ProvisioningTransaction id (Part 1 back-fills it from the parent
 *       transaction before the response is built). {@code src_object_id} = txn_source_id;
 *       {@code src_natural_key} = {@code txn_source_id|item_type|item_index} (a different, shorter formula than the PK).</li>
 *   <li>{@code source_object_type} per row, as Part 1's parser stamps it: {@code PERMISSION} items →
 *       {@code sailpoint.object.ProvisioningPlan$PermissionRequest}, all others →
 *       {@code sailpoint.object.ProvisioningPlan$AttributeRequest}.</li>
 *   <li>17 response fields: {@code value_json} is the only {@code jsonb}; {@code value}, {@code permission_target},
 *       {@code permission_rights} are text; {@code assignment} boolean; {@code item_index} integer.</li>
 *   <li>{@code record_hash} = Part 1's 16-field hash: every field EXCEPT {@code instance}.</li>
 * </ul>
 * Lineage: {@code IdentityIQ} / {@code native_iiq_java_api}. Part 1 keeps historical item rows ("absence does not prove
 * deletion"); Part 2 likewise never deletes.
 */
public final class ProvisioningItemRepository implements EntityWriter {

    public static final String RESOURCE = "provisioning-items";
    public static final String TABLE = "kf_provisioning_item";
    public static final String SOURCE_SYSTEM = "IdentityIQ";
    public static final String SOURCE_INTERFACE = "native_iiq_java_api";
    public static final String OBJECT_TYPE_ATTRIBUTE = "sailpoint.object.ProvisioningPlan$AttributeRequest";
    public static final String OBJECT_TYPE_PERMISSION = "sailpoint.object.ProvisioningPlan$PermissionRequest";

    /** The 17 response fields, in iiq_native.kf_provisioning_item order. */
    public static final List<ColumnSpec> BUSINESS = List.of(
            ColumnSpec.text("txn_source_id"),
            ColumnSpec.text("identity_name"),
            ColumnSpec.text("item_type"),
            ColumnSpec.text("operation"),
            ColumnSpec.text("application_name"),
            ColumnSpec.text("native_identity"),
            ColumnSpec.text("instance"),
            ColumnSpec.text("account_operation"),
            ColumnSpec.text("name"),
            ColumnSpec.text("value"),
            ColumnSpec.jsonb("value_json"),
            ColumnSpec.text("assignment_id"),
            ColumnSpec.bool("assignment"),
            ColumnSpec.text("permission_target"),
            ColumnSpec.text("permission_rights"),
            ColumnSpec.text("request_id"),
            ColumnSpec.integer("item_index"));

    private final String schema;
    private final String table;
    private final String createTableSql;
    private final String upsertSql;

    public ProvisioningItemRepository(String schema) {
        this.schema = SchemaName.validate(schema);
        this.table = this.schema + "." + TABLE;
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n")
                .append("    provisioningitemid uuid PRIMARY KEY");
        for (ColumnSpec c : BUSINESS) {
            ddl.append(",\n    ").append(c.name).append(' ').append(c.kind.ddlType);
        }
        ddl.append(",\n    record_hash text")
                .append(",\n    source_system text")
                .append(",\n    source_interface text")
                .append(",\n    source_object_type text")
                .append(",\n    src_object_id text")
                .append(",\n    src_natural_key text")
                .append(",\n    extraction_run_id text")
                .append(",\n    extracted_at timestamp with time zone NOT NULL DEFAULT now()")
                .append(",\n    is_deleted boolean NOT NULL DEFAULT false")
                .append(",\n    deleted_at timestamp with time zone")
                .append("\n)");
        this.createTableSql = ddl.toString();

        StringBuilder cols = new StringBuilder("provisioningitemid");
        StringBuilder vals = new StringBuilder("?::uuid");
        List<String> compared = new java.util.ArrayList<>();
        for (ColumnSpec c : BUSINESS) {
            cols.append(", ").append(c.name);
            vals.append(", ").append(c.kind.placeholder);
            compared.add(c.name);
        }
        compared.addAll(List.of("record_hash", "src_object_id", "src_natural_key"));
        List<String> lineage = List.of("source_system", "source_interface", "source_object_type", "extraction_run_id");
        for (String meta : List.of("record_hash", "source_system", "source_interface", "source_object_type",
                "src_object_id", "src_natural_key", "extraction_run_id")) {
            cols.append(", ").append(meta);
            vals.append(", ?");
        }
        this.upsertSql = "INSERT INTO " + table + " AS " + UpsertSql.ALIAS + " (" + cols + ") VALUES (" + vals + ") "
                + UpsertSql.tail("provisioningitemid", compared, lineage, true);
    }

    @Override
    public String entity() {
        return "ProvisioningItem";
    }

    @Override
    public String resource() {
        return RESOURCE;
    }

    @Override
    public String targetTable() {
        return table;
    }

    @Override
    public void ensureSchemaAndTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            st.execute(createTableSql);
        }
    }

    @Override
    public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String extractionRunId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            int i = 1;
            ps.setString(i++, id(row));
            for (ColumnSpec c : BUSINESS) {
                JsonColumnBinder.bind(ps, i++, c, row.get(c.name));
            }
            ps.setString(i++, recordHash(row));
            ps.setString(i++, SOURCE_SYSTEM);
            ps.setString(i++, SOURCE_INTERFACE);
            ps.setString(i++, objectType(row));
            ps.setString(i++, text(row, "txn_source_id")); // src_object_id: the parent transaction, as Part 1 binds it
            ps.setString(i++, naturalKey(row));
            ps.setString(i, extractionRunId);
            try (ResultSet rs = ps.executeQuery()) {
                return UpsertSql.outcome(rs);
            }
        }
    }

    /** Part 1 {@code NativeProvisioningItemRepository.canonicalItemId}; value_json as Part 1's compact JSON string. */
    /** The PK {@link #upsert} writes. */
    @Override
    public String key(JsonNode row) {
        return id(row);
    }

    @Override
    public String keyColumn() {
        return "provisioningitemid";
    }

    public static String id(JsonNode row) {
        return CanonicalIds.deterministicUuid("native-provisioning-item|" + text(row, "txn_source_id") + "|"
                + text(row, "item_type") + "|" + text(row, "operation") + "|" + text(row, "name") + "|"
                + json(row, "value_json") + "|" + text(row, "value") + "|" + integer(row, "item_index"));
    }

    /** Part 1 {@code NativeProvisioningItemRepository.derivedNaturalKey}. */
    public static String naturalKey(JsonNode row) {
        return text(row, "txn_source_id") + "|" + text(row, "item_type") + "|" + integer(row, "item_index");
    }

    /** Part 1 parser: PERMISSION items are PermissionRequests, every other item an AttributeRequest. */
    public static String objectType(JsonNode row) {
        return "PERMISSION".equals(text(row, "item_type")) ? OBJECT_TYPE_PERMISSION : OBJECT_TYPE_ATTRIBUTE;
    }

    /** Part 1 {@code NativeProvisioningItemRepository.recordHash}: 16 fields — all but {@code instance}. */
    public static String recordHash(JsonNode r) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("txn_source_id", text(r, "txn_source_id"));
        b.put("identity_name", text(r, "identity_name"));
        b.put("item_type", text(r, "item_type"));
        b.put("operation", text(r, "operation"));
        b.put("application_name", text(r, "application_name"));
        b.put("native_identity", text(r, "native_identity"));
        b.put("account_operation", text(r, "account_operation"));
        b.put("name", text(r, "name"));
        b.put("value", text(r, "value"));
        b.put("value_json", json(r, "value_json"));
        b.put("assignment_id", text(r, "assignment_id"));
        b.put("assignment", bool(r, "assignment"));
        b.put("permission_target", text(r, "permission_target"));
        b.put("permission_rights", text(r, "permission_rights"));
        b.put("request_id", text(r, "request_id"));
        b.put("item_index", integer(r, "item_index"));
        return RecordHash.of(b);
    }

    @Override
    public String ref(JsonNode row) {
        return "item #" + text(row, "item_index") + " (" + text(row, "item_type") + " " + text(row, "operation") + " "
                + text(row, "name") + "=" + text(row, "value") + ") of txn " + text(row, "txn_source_id");
    }

    /** Exposed for tests. */
    public String createTableSql() {
        return createTableSql;
    }

    /** Exposed for tests. */
    public String upsertSql() {
        return upsertSql;
    }
}

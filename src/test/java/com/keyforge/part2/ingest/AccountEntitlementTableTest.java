package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keyforge.part2.id.CanonicalIds;
import com.keyforge.part2.testsupport.RecordingJdbc;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.keyforge.part2.testsupport.RecordingJdbc.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_account_entitlement: Part 1 parity (golden fixture produced by Part 1's own NativeAccountEntitlementParser /
 * Repository / RestService.toJson — ATTRIBUTE, PERMISSION, TARGET_PERMISSION and a null-type row), the
 * type-dependent edge key, per-row object type, 14-field hash subset, trailing-column DDL and binding.
 */
class AccountEntitlementTableTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "accountentitlementid", "link_id", "identity_id", "identity_name", "application_id", "application_name",
            "native_identity", "instance", "attribute_name", "attribute_value", "record_hash", "source_system",
            "source_interface", "source_object_type", "extraction_run_id", "extracted_at", "is_deleted",
            "deleted_at", "type", "permission_target", "permission_rights", "permission_rights_list",
            "permission_annotation", "permission_aggregation_source", "permission_attributes");

    private static final Set<String> NOT_HASHED = Set.of("identity_name", "application_id");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo =
            new EntityRepository("iiq_native_part2_test", AccountEntitlementTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/account-entitlement-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), hash, line.substring(3)));
            }
        }
        assertEquals(4, out.size(), "golden fixture rows");
        return out;
    }

    // ── key ──

    @Test
    void keyAndRecordHashMatchPart1ForEveryEdgeType() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.hash(), AccountEntitlementTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), AccountEntitlementTable.accountEntitlementId(g.rest()), "key parity for " + g.rest());
        }
    }

    @Test
    void keyFormulaDependsOnEdgeType() throws Exception {
        List<Golden> gs = golden();
        assertEquals(CanonicalIds.deterministicUuid("native-account-entitlement|7f0001019f061fdc819f34b0ac345122"
                + "|groups|Ops – café ✓"), gs.get(0).id(), "ATTRIBUTE: link|attribute|value");
        assertEquals(CanonicalIds.deterministicUuid("native-account-entitlement|7f0001019f061fdc819f34b0ac345122"
                + "|PERMISSION|db1.payroll|read,write|direct"), gs.get(1).id(), "PERMISSION: link|type|target|rights|annotation");
        assertEquals(CanonicalIds.deterministicUuid("native-account-entitlement|null|null|null"), gs.get(3).id(),
                "null type -> ATTRIBUTE formula, nulls as text");

        // same link + target + rights, other kind -> different edge
        ObjectNode asTarget = gs.get(1).rest().deepCopy();
        asTarget.put("type", "TARGET_PERMISSION");
        assertNotEquals(gs.get(1).id(), AccountEntitlementTable.accountEntitlementId(asTarget));
        // for ATTRIBUTE edges the permission fields don't move the key
        ObjectNode attr = gs.get(0).rest().deepCopy();
        attr.put("permission_target", "x").put("identity_name", "y");
        assertEquals(gs.get(0).id(), AccountEntitlementTable.accountEntitlementId(attr));
    }

    // ── per-row object type ──

    @Test
    void objectTypeFollowsEdgeKind() throws Exception {
        List<Golden> gs = golden();
        assertEquals("sailpoint.object.Link.entitlementAttributes", AccountEntitlementTable.objectType(gs.get(0).rest()));
        assertEquals("sailpoint.object.Link.permissions", AccountEntitlementTable.objectType(gs.get(1).rest()));
        assertEquals("sailpoint.object.Link.targetPermissions", AccountEntitlementTable.objectType(gs.get(2).rest()));
        assertEquals("sailpoint.object.Link.entitlementAttributes", AccountEntitlementTable.objectType(gs.get(3).rest()),
                "null type = ATTRIBUTE");

        ObjectNode unknown = gs.get(0).rest().deepCopy();
        unknown.put("type", "SOMETHING_ELSE");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> repo.upsert(new RecordingJdbc().connection(), unknown, "run-1"));
        assertTrue(e.getMessage().contains("SOMETHING_ELSE"), e.getMessage());
    }

    // ── hash subset ──

    @Test
    void hashCoversAllButIdentityNameAndApplicationId() throws Exception {
        Golden g = golden().get(1);
        for (ColumnSpec c : AccountEntitlementTable.SPEC.allBusiness()) {
            ObjectNode changed = g.rest().deepCopy();
            changed.put(c.name, "changed-" + c.name);
            if (NOT_HASHED.contains(c.name)) {
                assertEquals(g.hash(), AccountEntitlementTable.recordHash(changed), c.name + " is NOT hashed in Part 1");
            } else {
                assertNotEquals(g.hash(), AccountEntitlementTable.recordHash(changed), c.name + " must be hashed");
            }
        }
    }

    @Test
    void responseFieldsAreExactlyTheMappedColumns() throws Exception {
        Set<String> responseKeys = new HashSet<>();
        golden().get(0).rest().fieldNames().forEachRemaining(responseKeys::add);
        Set<String> mapped = new HashSet<>();
        AccountEntitlementTable.SPEC.allBusiness().forEach(c -> mapped.add(c.name));
        assertEquals(responseKeys, mapped);
        assertEquals(16, mapped.size());
    }

    // ── DDL / upsert / binding ──

    @Test
    void ddlAndUpsertMatchIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols, "type + permission_* sit after deleted_at, as in iiq_native");
        assertTrue(ddl.contains("accountentitlementid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("permission_rights_list jsonb"));
        assertTrue(ddl.contains("permission_attributes jsonb"));

        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (accountentitlementid) DO UPDATE SET"));
        assertTrue(sql.contains("type = EXCLUDED.type"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(22, vals.chars().filter(ch -> ch == '?').count(), "pk + 16 + record_hash + 4 lineage");
        assertEquals("account-entitlements", AccountEntitlementTable.SPEC.resource());
    }

    @Test
    void bindsPermissionEdgeWithItsObjectType() throws Exception {
        Golden g = golden().get(1);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-17"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals(g.id(), b.get(1));
        assertEquals("7f0001019f061fdc819f34b0ac345122", b.get(col("link_id")));
        assertSame(NULL, b.get(col("attribute_name")));
        assertEquals("PERMISSION", b.get(col("type")));
        assertEquals("db1.payroll", b.get(col("permission_target")));
        assertEquals("[\"read\",\"write\"]", b.get(col("permission_rights_list")));
        assertEquals("{\"n\":1.5,\"z\":null}", b.get(col("permission_attributes")));

        int hashIdx = 2 + AccountEntitlementTable.SPEC.allBusiness().size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.Link.permissions", b.get(hashIdx + 3), "per-row object type");
        assertEquals("run-17", b.get(hashIdx + 4));
        assertEquals(hashIdx + 4, b.size());

        RecordingJdbc attr = new RecordingJdbc();
        repo.upsert(attr.connection(), golden().get(0).rest(), "run-17");
        assertEquals("sailpoint.object.Link.entitlementAttributes", attr.executions.get(0).get(hashIdx + 3));
        assertEquals("[]", attr.executions.get(0).get(col("permission_rights_list")), "empty array kept");
        assertSame(NULL, attr.executions.get(0).get(col("permission_attributes")));
    }

    private static int col(String name) {
        List<ColumnSpec> all = AccountEntitlementTable.SPEC.allBusiness();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

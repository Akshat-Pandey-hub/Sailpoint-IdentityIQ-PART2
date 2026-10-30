package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.testsupport.RecordingJdbc;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.testsupport.RecordingJdbc.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_role_entitlement. The golden fixture was produced by Part 1's own code (NativeRoleRelationshipParser,
 * NativeRoleEntitlementRepository.id/hash, NativeRoleEntitlementRestService.toJson, and the mapper's naturalKey
 * helpers). Its FIRST row is the real row Part 1 persisted in iiq_native.sql (pg_restore of kf_role_entitlement),
 * so the derived src_natural_key / roleentitlementid / record_hash are checked against actual Part 1 data.
 */
class RoleEntitlementRepositoryTest {

    /** Column order of iiq_native.kf_role_entitlement exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "roleentitlementid", "source_role_id", "role_id", "role_name", "entitlement_id", "entitlement_type",
            "application_id", "application_name", "profile_ordinal", "constraint_path", "filter_expression",
            "filter_value", "filter_operation", "attribute_name", "attribute_value", "permission_target",
            "permission_rights", "permission_rights_list", "permission_annotation", "source_system",
            "source_interface", "source_object_type", "src_object_id", "src_natural_key", "extraction_run_id",
            "extracted_at", "record_hash", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final RoleEntitlementRepository repo = new RoleEntitlementRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String entitlementId, String naturalKey, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/role-entitlement-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, ent = null, nk = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("ENT=")) ent = line.substring(4).isEmpty() ? null : line.substring(4);
                else if (line.startsWith("NK=")) nk = line.substring(3);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), ent, nk, hash, line.substring(3)));
            }
        }
        assertEquals(5, out.size(), "golden fixture rows");
        return out;
    }

    // ── derivations vs real Part 1 data ──

    @Test
    void reproducesTheRealRowPart1PersistedInIiqNative() throws Exception {
        Golden g = golden().get(0);
        RoleEntitlementRepository.Edge e = RoleEntitlementRepository.Edge.of(g.rest());
        // values copied from the iiq_native.sql COPY data for kf_role_entitlement
        assertEquals("32:7f000101a0cf1f4c81a111f0f19b57121:018:PROFILE_CONSTRAINT1:032:7f0001019eb91d3c819f01f0c4127efb"
                + "11:posixgroups12:CONTAINS_ALL53:[48:java.lang.String:cn=hr,ou=rocktar,dc=moli,dc=org]-1:-1:2:[]-1:",
                e.naturalKey());
        assertEquals("ff233151-36b9-3ba1-a2b2-16bd811a5533", e.id());
        assertEquals("7f000101-a0cf-1f4c-81a1-11f0f19b5712", e.roleId());
        assertEquals("b62841ae7d20a74cf1f2e528bc90b69f6864ff59023f1cdbac6b24e6c1b6812d", e.recordHash(null));
    }

    @Test
    void naturalKeyIdAndHashMatchPart1ForEveryShape() throws Exception {
        for (Golden g : golden()) {
            RoleEntitlementRepository.Edge e = RoleEntitlementRepository.Edge.of(g.rest());
            assertEquals(g.naturalKey(), e.naturalKey(), "src_natural_key for " + g.rest());
            assertEquals(g.id(), e.id(), "roleentitlementid for " + g.rest());
            assertEquals(g.hash(), e.recordHash(g.entitlementId()), "record_hash for " + g.rest());
        }
    }

    @Test
    void responseOmitsTheDerivedColumns() throws Exception {
        JsonNode rest = golden().get(0).rest();
        for (String derived : List.of("roleentitlementid", "role_id", "entitlement_id", "src_object_id",
                "src_natural_key", "record_hash")) {
            assertTrue(!rest.has(derived), derived + " must be derived by Part 2, not read from the response");
        }
        assertEquals(16, rest.size());
    }

    // ── entitlement_id resolution ("do not guess") ──

    @Test
    void eqConstraintLinksTheUniqueMatchingEntitlement() throws Exception {
        Golden g = golden().get(1); // EQ on EntraTarget groups=ops
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.selectAnswer = (sql, params) -> List.of(g.entitlementId());
        repo.upsert(jdbc.connection(), g.rest(), "run-1");

        assertEquals(Map.of(1, "EntraTarget", 2, "groups", 3, "ops"), jdbc.selects.get(0), "lookup by app/attribute/value");
        Map<Integer, Object> b = jdbc.executions.get(0);
        assertEquals(g.entitlementId(), b.get(5), "entitlement_id linked");
        assertEquals(g.hash(), b.get(26), "record_hash includes the linked entitlement_id");
    }

    @Test
    void ambiguousOrMissingMatchIsNotLinked() throws Exception {
        Golden g = golden().get(1);
        RecordingJdbc two = new RecordingJdbc();
        two.selectAnswer = (sql, params) -> List.of("11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222");
        repo.upsert(two.connection(), g.rest(), "run-1");
        assertSame(NULL, two.executions.get(0).get(5), "two matches -> not linked");

        RecordingJdbc none = new RecordingJdbc();
        repo.upsert(none.connection(), g.rest(), "run-1");
        assertSame(NULL, none.executions.get(0).get(5), "no match -> not linked");
    }

    @Test
    void nonEqConstraintsAndPermissionsNeverLookUp() throws Exception {
        for (int i : new int[]{0, 2, 3, 4}) { // CONTAINS_ALL, IN, PROFILE_PERMISSION, ISNULL
            RecordingJdbc jdbc = new RecordingJdbc();
            jdbc.selectAnswer = (sql, params) -> List.of("should-not-be-used");
            repo.upsert(jdbc.connection(), golden().get(i).rest(), "run-1");
            assertTrue(jdbc.selects.isEmpty(), "no lookup for row " + i);
            assertSame(NULL, jdbc.executions.get(0).get(5));
        }
    }

    // ── binding ──

    @Test
    void bindsDerivedColumnsAndResponseValuesAsReceived() throws Exception {
        Golden g = golden().get(4); // non-GUID role id, null filter value, ordinal 3
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-21"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals(g.id(), b.get(1));
        assertEquals("not-a-guid", b.get(2), "source_role_id verbatim");
        assertEquals(RoleEntitlementRepository.Edge.of(g.rest()).roleId(), b.get(3), "role_id derived");
        assertEquals(3, b.get(9), "profile_ordinal int");
        assertEquals(g.rest().path("filter_value").isNull() || g.rest().get("filter_value") == null ? RecordingJdbc.NULL : g.rest().get("filter_value").toString(),
                b.get(12) == null ? RecordingJdbc.NULL : b.get(12), "filter_value exactly as received (null stays NULL)");
        Object rights = g.rest().get("permission_rights_list");
        assertEquals(rights == null || g.rest().get("permission_rights_list").isNull() ? null : rights.toString(), b.get(18),
                "permission_rights_list exactly as received");
        assertEquals("IdentityIQ", b.get(20));
        assertEquals("native_iiq_java_api", b.get(21));
        assertEquals("sailpoint.object.Bundle", b.get(22));
        assertEquals("not-a-guid", b.get(23), "src_object_id = source_role_id");
        assertEquals(g.naturalKey(), b.get(24), "src_natural_key derived");
        assertEquals("run-21", b.get(25));
        assertEquals(g.hash(), b.get(26));
        assertEquals(26, b.size());

        RecordingJdbc perm = new RecordingJdbc();
        repo.upsert(perm.connection(), golden().get(3).rest(), "run-21");
        assertEquals("[\"read\",\"write\"]", perm.executions.get(0).get(18));
        assertEquals("PROFILE_PERMISSION", perm.executions.get(0).get(6));
    }

    @Test
    void sameEdgeKeepsItsKeyAcrossRuns() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), golden().get(2).rest(), "run-1");
        repo.upsert(jdbc.connection(), golden().get(2).rest(), "run-2");
        assertEquals(jdbc.executions.get(0).get(1), jdbc.executions.get(1).get(1));
        assertEquals(jdbc.executions.get(0).get(26), jdbc.executions.get(1).get(26));
    }

    // ── DDL / precondition ──

    @Test
    void ddlMatchesIiqNativeAndUpsertConflictsOnTheKey() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("source_role_id text NOT NULL"));
        assertTrue(ddl.contains("entitlement_type text NOT NULL"));
        assertTrue(ddl.contains("src_natural_key text NOT NULL"));
        assertTrue(ddl.contains("role_id uuid") && ddl.contains("entitlement_id uuid"));

        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (roleentitlementid) DO UPDATE SET"));
        assertTrue(sql.contains("is_deleted = false") && sql.contains("deleted_at = null"));
        assertEquals(26, sql.chars().filter(ch -> ch == '?').count());
    }

    @Test
    void refusesToRunWithoutKfEntitlementInTheSameSchema() {
        RecordingJdbc missing = new RecordingJdbc();
        missing.selectAnswer = (sql, params) -> java.util.Collections.singletonList(null);
        SQLException e = assertThrows(SQLException.class, () -> repo.ensureSchemaAndTable(missing.connection()));
        assertTrue(e.getMessage().contains("import-entitlement"), e.getMessage());
        assertEquals("iiq_native_part2_test.kf_entitlement", missing.selects.get(0).get(1));

        RecordingJdbc present = new RecordingJdbc();
        present.selectAnswer = (sql, params) -> List.of("iiq_native_part2_test.kf_entitlement");
        assertDoesNotThrowSql(() -> repo.ensureSchemaAndTable(present.connection()));
        assertTrue(present.ddl.get(1).contains("kf_role_entitlement"));
    }

    @Test
    void fallbackRoleIdForNonGuidId() throws Exception {
        assertNull(com.keyforge.part2.id.CanonicalIds.canonicalUuid("not-a-guid"));
        assertEquals(com.keyforge.part2.id.CanonicalIds.deterministicUuid("native-role|not-a-guid"),
                RoleEntitlementRepository.Edge.of(golden().get(4).rest()).roleId());
    }

    private interface SqlRunnable { void run() throws SQLException; }

    private static void assertDoesNotThrowSql(SqlRunnable r) {
        try {
            r.run();
        } catch (SQLException e) {
            throw new AssertionError("unexpected: " + e.getMessage(), e);
        }
    }
}

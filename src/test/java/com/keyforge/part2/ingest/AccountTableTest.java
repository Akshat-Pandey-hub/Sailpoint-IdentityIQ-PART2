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
import java.util.List;
import java.util.Map;

import static com.keyforge.part2.testsupport.RecordingJdbc.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_account: Part 1 parity (golden fixture produced by Part 1's own NativeLinkParser / NativeLinkRepository /
 * NativeAccountRestService.toJson — incl. both fallback-key cases), DDL/upsert contract, and binding.
 */
class AccountTableTest {

    /** Column order of iiq_native.kf_account exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "accountid", "source_id", "link_uuid", "native_identity", "display_name", "displayable_name",
            "instance", "component_ids", "application_id", "application_name", "identity_id", "identity_name",
            "disabled", "locked", "composite", "manually_correlated", "has_entitlements", "iiq_disabled",
            "iiq_locked", "permissions", "target_permissions", "attributes", "entitlement_attributes",
            "attribute_metadata", "created_at", "modified_at", "last_refresh", "last_target_aggregation",
            "significant_modified", "prior_significant_modified", "record_hash", "source_system",
            "source_interface", "source_object_type", "extraction_run_id", "extracted_at", "is_deleted",
            "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", AccountTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/account-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), hash, line.substring(3)));
            }
        }
        assertEquals(3, out.size(), "golden fixture rows");
        return out;
    }

    // ── parity with Part 1 ──

    @Test
    void recordHashAndAccountIdMatchPart1IncludingBothFallbacks() throws Exception {
        List<Golden> gs = golden();
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac345122", gs.get(0).id(), "canonical form of source_id");
        for (Golden g : gs) {
            assertEquals(g.hash(), AccountTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), AccountTable.accountId(g.rest()), "accountid parity for " + g.rest());
        }
        // no source_id: seed is the natural key "application_name|native_identity" ...
        assertEquals(CanonicalIds.deterministicUuid("native-link|HR DB|jdoe"), gs.get(1).id());
        // ... and a null part is concatenated as the text "null", exactly like Part 1
        assertEquals(CanonicalIds.deterministicUuid("native-link|null|orphan"), gs.get(2).id());
    }

    @Test
    void everyBusinessFieldIsHashed() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : AccountTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), AccountTable.recordHash(changed), c.name + " must be part of record_hash");
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> responseKeys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(responseKeys::add);
        assertEquals(responseKeys, AccountTable.BUSINESS.stream().map(c -> c.name).toList());
    }

    // ── DDL / upsert contract ──

    @Test
    void ddlMatchesIiqNativeColumnOrderAndPk() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("accountid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("attribute_metadata jsonb"));
        assertTrue(ddl.contains("prior_significant_modified timestamp with time zone"));
    }

    @Test
    void upsertConflictsOnAccountid() {
        String sql = repo.upsertSql();
        assertTrue(sql.startsWith("INSERT INTO iiq_native_part2_test.kf_account (accountid, source_id,"));
        assertTrue(sql.contains("ON CONFLICT (accountid) DO UPDATE SET"));
        assertFalse(sql.contains("accountid = EXCLUDED"), "pk is never rewritten");
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        // pk + 29 business + record_hash + 4 lineage params
        assertEquals(35, vals.chars().filter(ch -> ch == '?').count());
        assertEquals("accounts", AccountTable.SPEC.resource());
    }

    // ── binding ──

    @Test
    void bindsKeyBusinessFieldsHashAndLineage() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-9"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9f06-1fdc-819f-34b0ac345122", b.get(1));
        assertEquals("7f0001019f061fdc819f34b0ac345122", b.get(col("source_id")), "source_id verbatim");
        assertEquals("{0a1b2c3d-1111-2222-3333-444455556666}", b.get(col("link_uuid")), "link uuid stays text");
        assertEquals("7f00010198421229819849ebca230c82", b.get(col("identity_id")), "raw IIQ identity id");
        assertSame(NULL, b.get(col("component_ids")));
        assertEquals(Boolean.TRUE, b.get(col("manually_correlated")));
        assertEquals(Boolean.FALSE, b.get(col("disabled")));
        assertEquals("[]", b.get(col("target_permissions")), "empty array kept");
        assertEquals("{\"memberOf\":[\"CN=ops\",\"CN=dev\"]}", b.get(col("entitlement_attributes")));
        assertSame(NULL, b.get(col("last_target_aggregation")));
        assertEquals("2026-09-30T14:44:40.123456789Z", b.get(col("significant_modified")), "nanos preserved");

        int hashIdx = 2 + AccountTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.Link", b.get(hashIdx + 3));
        assertEquals("run-9", b.get(hashIdx + 4));
        assertEquals(hashIdx + 4, b.size());
    }

    @Test
    void fallbackKeyIsStableAcrossRuns() throws Exception {
        JsonNode noId = golden().get(1).rest();
        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), noId, "run-1");
        repo.upsert(jdbc.connection(), noId, "run-2");
        assertEquals(jdbc.executions.get(0).get(1), jdbc.executions.get(1).get(1));
        assertSame(NULL, jdbc.executions.get(0).get(col("disabled")), "null boolean -> SQL NULL");
    }

    private static int col(String name) {
        for (int i = 0; i < AccountTable.BUSINESS.size(); i++) {
            if (AccountTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

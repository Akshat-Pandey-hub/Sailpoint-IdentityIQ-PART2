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
 * kf_workgroup: Part 1 parity (golden fixture produced by Part 1's own NativeWorkgroupParser /
 * NativeWorkgroupRepository / NativeWorkgroupRestService.toJson — incl. null and non-GUID source_id
 * fallbacks), DDL/upsert contract, and binding.
 */
class WorkgroupTableTest {

    /** Column order of iiq_native.kf_workgroup exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "workgroupid", "source_id", "name", "display_name", "displayable_name", "email", "type",
            "description", "notification_option", "inactive", "is_workgroup", "owner_id", "owner_name",
            "capabilities", "attributes", "created_at", "modified_at", "record_hash", "source_system",
            "source_interface", "source_object_type", "extraction_run_id", "extracted_at", "is_deleted",
            "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", WorkgroupTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/workgroup-part1-golden.txt");
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
    void recordHashAndWorkgroupIdMatchPart1IncludingFallbacks() throws Exception {
        List<Golden> gs = golden();
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3451aa", gs.get(0).id(), "canonical form of source_id");
        for (Golden g : gs) {
            assertEquals(g.hash(), WorkgroupTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
            assertEquals(g.id(), WorkgroupTable.workgroupId(g.rest()), "workgroupid parity for " + g.rest());
        }
        assertEquals(CanonicalIds.deterministicUuid("native-workgroup|No Id Group"), gs.get(1).id(), "null id -> name");
        assertEquals(CanonicalIds.deterministicUuid("native-workgroup|not-a-guid"), gs.get(2).id(), "non-GUID id -> id");
    }

    @Test
    void everyBusinessFieldIsHashed() throws Exception {
        Golden g = golden().get(0);
        for (ColumnSpec c : WorkgroupTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), WorkgroupTable.recordHash(changed), c.name + " must be part of record_hash");
        }
    }

    @Test
    void responseFieldsAreExactlyTheBusinessColumns() throws Exception {
        List<String> responseKeys = new ArrayList<>();
        golden().get(0).rest().fieldNames().forEachRemaining(responseKeys::add);
        assertEquals(responseKeys, WorkgroupTable.BUSINESS.stream().map(c -> c.name).toList());
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
        assertTrue(ddl.contains("workgroupid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("capabilities jsonb"));
    }

    @Test
    void upsertConflictsOnWorkgroupid() {
        String sql = repo.upsertSql();
        assertTrue(sql.startsWith("INSERT INTO iiq_native_part2_test.kf_workgroup (workgroupid, source_id,"));
        assertTrue(sql.contains("ON CONFLICT (workgroupid) DO UPDATE SET"));
        assertFalse(sql.contains("workgroupid = EXCLUDED"), "pk is never rewritten");
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        // pk + 16 business + record_hash + 4 lineage params
        assertEquals(22, vals.chars().filter(ch -> ch == '?').count());
        assertEquals("workgroups", WorkgroupTable.SPEC.resource());
    }

    // ── binding ──

    @Test
    void bindsKeyBusinessFieldsHashAndIdentityObjectType() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-3"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3451aa", b.get(1));
        assertEquals("7f0001019f061fdc819f34b0ac3451aa", b.get(col("source_id")), "source_id verbatim");
        assertEquals("MembersOnly", b.get(col("notification_option")));
        assertSame(NULL, b.get(col("type")));
        assertEquals(Boolean.TRUE, b.get(col("is_workgroup")));
        assertEquals(Boolean.FALSE, b.get(col("inactive")));
        assertEquals("[{\"name\":\"ApproverCapability\",\"rights\":[\"ViewIdentity\"]}]", b.get(col("capabilities")));
        assertEquals("2026-09-30T14:44:40.123456789Z", b.get(col("modified_at")));

        int hashIdx = 2 + WorkgroupTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.Identity", b.get(hashIdx + 3), "a workgroup is an IIQ Identity");
        assertEquals("run-3", b.get(hashIdx + 4));
        assertEquals(hashIdx + 4, b.size());
    }

    @Test
    void emptyNestedValuesKeptAndNullsBound() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), golden().get(1).rest(), "run-3");
        Map<Integer, Object> b = jdbc.executions.get(0);
        assertEquals("[]", b.get(col("capabilities")));
        assertEquals("{}", b.get(col("attributes")));
        assertSame(NULL, b.get(col("inactive")));
        assertSame(NULL, b.get(col("created_at")));
    }

    private static int col(String name) {
        for (int i = 0; i < WorkgroupTable.BUSINESS.size(); i++) {
            if (WorkgroupTable.BUSINESS.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

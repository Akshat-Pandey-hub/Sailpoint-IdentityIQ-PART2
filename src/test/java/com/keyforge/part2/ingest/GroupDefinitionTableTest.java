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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_group_definition. The live endpoint and the iiq_native dump are both EMPTY, so the golden fixture was produced by
 * running Part 1's own NativeGroupDefinitionParser / RestService / Repository over plugin-shaped rows: a POPULATION
 * with a filter and owner, a factory-generated GROUP, a non-GUID id and a null id. Covers key + fallbacks, the
 * all-15-field hash, DDL, binding.
 */
class GroupDefinitionTableTest {

    /** Column order of iiq_native.kf_group_definition exactly as in iiq_native.sql. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "groupid", "source_id", "name", "type", "factory_id", "factory_name", "filter_expression", "is_private",
            "indexed", "null_group", "name_unique", "owner_id", "owner_name", "last_refresh", "created_at",
            "modified_at", "record_hash", "source_system", "source_interface", "source_object_type",
            "extraction_run_id", "extracted_at", "is_deleted", "deleted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final EntityRepository repo = new EntityRepository("iiq_native_part2_test", GroupDefinitionTable.SPEC);

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/group-definition-part1-golden.txt");
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

    @Test
    void keyAndHashMatchPart1IncludingFallbacks() throws Exception {
        List<Golden> gs = golden();
        for (Golden g : gs) {
            assertEquals(g.id(), GroupDefinitionTable.groupId(g.rest()), "groupid parity for " + g.rest());
            assertEquals(g.hash(), GroupDefinitionTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
        }
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac35b1c1", gs.get(0).id());
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac35b2c2", gs.get(1).id(), "already-dashed id");
        assertEquals(CanonicalIds.deterministicUuid("native-groupdefinition|group-not-a-guid"), gs.get(2).id(),
                "non-GUID id -> id");
        assertEquals(CanonicalIds.deterministicUuid("native-groupdefinition|No Id Population"), gs.get(3).id(),
                "null id -> name");
    }

    @Test
    void everyFieldIsHashed() throws Exception {
        Golden g = golden().get(1);
        for (ColumnSpec c : GroupDefinitionTable.BUSINESS) {
            ObjectNode changed = g.rest().deepCopy();
            switch (c.kind) {
                case BOOLEAN -> changed.put(c.name, !g.rest().path(c.name).asBoolean());
                case TIMESTAMPTZ -> changed.put(c.name, "2001-01-01T00:00:00Z");
                default -> changed.put(c.name, "changed-" + c.name);
            }
            assertNotEquals(g.hash(), GroupDefinitionTable.recordHash(changed), c.name + " must be hashed");
        }
    }

    @Test
    void responseFieldsAreExactlyTheMappedColumns() throws Exception {
        Set<String> keys = new HashSet<>();
        golden().get(0).rest().fieldNames().forEachRemaining(keys::add);
        Set<String> mapped = new HashSet<>();
        GroupDefinitionTable.BUSINESS.forEach(c -> mapped.add(c.name));
        assertEquals(keys, mapped);
        assertEquals(15, mapped.size());
    }

    @Test
    void ddlMatchesIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("groupid uuid PRIMARY KEY"));
        assertTrue(ddl.contains("is_private boolean") && ddl.contains("name_unique boolean")
                && ddl.contains("filter_expression text"));

        String sql = repo.upsertSql();
        assertTrue(sql.contains("ON CONFLICT (groupid) DO UPDATE SET"));
        String vals = sql.substring(sql.indexOf("VALUES (") + 8, sql.indexOf(") ON CONFLICT"));
        assertEquals(21, vals.chars().filter(ch -> ch == '?').count(), "pk + 15 + record_hash + 4 lineage");
        assertEquals("group-definitions", GroupDefinitionTable.SPEC.resource());
    }

    @Test
    void bindsGroupDefinitionFieldsHashAndLineage() throws Exception {
        Golden g = golden().get(1);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-gd"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals("7f000101-9f06-1fdc-819f-34b0ac35b2c2", b.get(1));
        assertEquals("GROUP", b.get(col("type")));
        assertEquals("7f0001019f061fdc819f34b0ac35a0f0", b.get(col("factory_id")), "factory id kept as raw text");
        assertEquals("Location", b.get(col("factory_name")));
        assertEquals("location == \"Pune\"", b.get(col("filter_expression")));
        assertEquals(Boolean.TRUE, b.get(col("is_private")));
        assertEquals(Boolean.FALSE, b.get(col("null_group")));
        assertSame(NULL, b.get(col("owner_id")));
        assertEquals("2026-10-02T15:23:51.988Z", b.get(col("last_refresh")));
        assertSame(NULL, b.get(col("modified_at")));

        int hashIdx = 2 + GroupDefinitionTable.BUSINESS.size();
        assertEquals(g.hash(), b.get(hashIdx));
        assertEquals("IdentityIQ", b.get(hashIdx + 1));
        assertEquals("native_iiq_java_api", b.get(hashIdx + 2));
        assertEquals("sailpoint.object.GroupDefinition", b.get(hashIdx + 3));
        assertEquals("run-gd", b.get(hashIdx + 4));
    }

    private static int col(String name) {
        List<ColumnSpec> all = GroupDefinitionTable.BUSINESS;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equals(name)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException(name);
    }
}

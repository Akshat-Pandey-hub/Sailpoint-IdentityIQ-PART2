package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keyforge.part2.testsupport.RecordingJdbc;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_workitem_archive. The live endpoint and the iiq_native dump are both EMPTY, so the golden fixture was produced by
 * running Part 1's own NativeWorkItemArchiveParser / RestService / Repository over plugin-shaped rows (an approval
 * archive linked to an identity request, a certification archive, a non-GUID id and a null id). Covers the key rule
 * (canonical, no fallback → refusal), the 29-field hash, the CEC lineage bindings, append-only SQL + SKIPPED, DDL.
 */
class WorkItemArchiveRepositoryTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "archiveid", "source_id", "work_item_id", "name", "type", "state", "level", "requester", "assignee",
            "owner_name", "completer", "completion_comments", "is_signed", "target_class", "target_id", "target_name",
            "identity_request_id", "certification_id", "certification_entity_id", "certification_item_id",
            "entity_type", "signoffs", "comments", "owner_history", "system_attributes", "attributes", "created_at",
            "modified_at", "expiration_ts", "archived_ts", "record_hash", "src_system", "src_object_type",
            "src_object_id", "src_natural_key", "src_created", "src_modified", "src_event_ts", "src_interface",
            "extraction_run_id", "raw_ref", "derived_at", "extracted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkItemArchiveRepository repo = new WorkItemArchiveRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String hash, String id, String lineage) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/workitem-archive-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null, id = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) id = line.substring(3);
                else if (line.startsWith("LINEAGE=")) out.add(new Golden(mapper.readTree(rest), hash, id, line.substring(8)));
            }
        }
        assertEquals(4, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void keyAndHashMatchPart1() throws Exception {
        List<Golden> gs = golden();
        for (Golden g : gs) {
            assertEquals(g.hash(), WorkItemArchiveRepository.recordHash(g.rest()), "record_hash parity for " + g.rest());
            if (!"THROWS".equals(g.id())) {
                assertEquals(g.id(), WorkItemArchiveRepository.id(g.rest()), "archiveid parity");
            }
        }
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac34a1b2", gs.get(0).id(), "canonical 32-hex id");
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac34c3d4", gs.get(1).id(), "already-dashed id");
        assertNotEquals("7f000101-9f06-1fdc-819f-1058aa0011a0", gs.get(0).id(),
                "keyed on the archive's own id, not on work_item_id");
    }

    @Test
    void nonGuidOrNullIdIsRefusedLikePart1() throws Exception {
        List<Golden> gs = golden();
        assertEquals("THROWS", gs.get(2).id(), "Part 1 refuses a non-GUID id (no fallback)");
        assertEquals("THROWS", gs.get(3).id(), "Part 1 refuses a null id");
        assertThrows(IllegalArgumentException.class, () -> WorkItemArchiveRepository.id(gs.get(2).rest()));
        assertThrows(IllegalArgumentException.class, () -> WorkItemArchiveRepository.id(gs.get(3).rest()));
        assertThrows(IllegalArgumentException.class,
                () -> repo.upsert(new RecordingJdbc().connection(), gs.get(2).rest(), "run-x"), "row fails, nothing bound");
    }

    @Test
    void hashCoversAllTwentyNineFields() throws Exception {
        Golden g = golden().get(0);
        List<String> fields = new ArrayList<>();
        g.rest().fieldNames().forEachRemaining(fields::add);
        assertEquals(29, fields.size(), "the response carries 29 fields");
        for (String f : fields) {
            ObjectNode changed = g.rest().deepCopy();
            switch (f) {
                case "is_signed" -> changed.put(f, true);
                case "created_at", "modified_at", "expiration_ts", "archived_ts" -> changed.put(f, "2001-01-01T00:00:00Z");
                case "signoffs", "comments", "owner_history" -> changed.putArray(f).add("other");
                case "system_attributes", "attributes" -> changed.putObject(f).put("other", 1);
                default -> changed.put(f, "changed-" + f);
            }
            assertNotEquals(g.hash(), WorkItemArchiveRepository.recordHash(changed), f + " must be hashed");
        }
    }

    @Test
    void bindsBusinessFieldsAndTheCecLineageEnvelopeLikePart1() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-wia"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals(g.id(), b.get(1));
        assertEquals("7f0001019f061fdc819f34b0ac34a1b2", b.get(2));
        assertEquals("7f0001019f061fdc819f1058aa0011a0", b.get(3), "work_item_id = the archived work item (raw id)");
        assertEquals("Alexander Evans – café ✓", b.get(11), "completer verbatim");
        assertEquals(Boolean.FALSE, b.get(13), "is_signed boolean");
        assertEquals("0000000042", b.get(17), "identity_request_id");
        assertEquals("[]", b.get(22), "signoffs jsonb");
        assertTrue(((String) b.get(24)).contains("\"n\":1.5"), "owner_history compact JSON");
        assertEquals("2026-06-27T02:33:29.128Z", b.get(27));
        assertEquals("2026-06-27T02:40:02Z", b.get(30), "archived_ts");
        assertEquals(g.hash(), b.get(31));
        assertEquals("IdentityIQ", b.get(32));
        assertEquals("sailpoint.object.WorkItemArchive", b.get(33));
        assertEquals("7f0001019f061fdc819f34b0ac34a1b2", b.get(34), "src_object_id = source_id");
        assertEquals("0000000042", b.get(35), "src_natural_key = archive name");
        assertEquals("2026-06-27T02:33:29.128Z", b.get(36), "src_created");
        assertEquals("2026-06-27T02:40:01.500Z", b.get(37), "src_modified");
        assertEquals("2026-06-27T02:40:02Z", b.get(38), "src_event_ts = archival time");
        assertEquals("native_iiq_java_api", b.get(39));
        assertEquals("run-wia", b.get(40));
        assertNull(b.get(41), "raw_ref NULL");
        assertEquals(RecordingJdbc.NULL, b.get(42), "derived_at NULL");
        assertEquals(42, b.size());
        assertEquals("IdentityIQ|native_iiq_java_api|sailpoint.object.WorkItemArchive|0000000042|2026-06-27T02:40:02Z",
                g.lineage(), "Part 1's lineage for this row: natural key = name, event ts = archived");

        RecordingJdbc cert = new RecordingJdbc();
        repo.upsert(cert.connection(), golden().get(1).rest(), "run-wia");
        Map<Integer, Object> c = cert.executions.get(0);
        assertEquals(Boolean.TRUE, c.get(13));
        assertEquals("7f0001019f061fdc819f34b0ac3471dd", c.get(18), "certification_id");
        assertEquals("Identity", c.get(21), "entity_type");
        assertNull(c.get(37), "src_modified NULL when modified_at is null");
    }

    @Test
    void existingRowsAreUpdatedOnlyWhenADataColumnChanged() throws Exception {
        String sql = repo.appendSql();
        assertTrue(sql.contains("ON CONFLICT (archiveid) DO UPDATE SET"));
        String where = sql.substring(sql.indexOf(" WHERE ROW("));
        assertTrue(where.contains(") IS DISTINCT FROM ROW("), "written only when a data column differs");
        assertFalse(where.contains("extraction_run_id"), "a new run alone never rewrites a row");
        assertTrue(sql.endsWith("RETURNING (xmax = 0)"));
        assertEquals(42, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(EntityRepository.Outcome.SKIPPED, repo.upsert(noRowsReturned(), golden().get(0).rest(), "run-wia2"));
    }

    @Test
    void ddlMatchesIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("archiveid uuid PRIMARY KEY") && ddl.contains("is_signed boolean")
                && ddl.contains("owner_history jsonb") && ddl.contains("archived_ts timestamp with time zone"));
        assertFalse(ddl.contains("is_deleted"), "append-only, no soft delete");
        assertEquals("workitem-archives", repo.resource());
    }

    /** A connection whose INSERT … RETURNING yields no row (what PostgreSQL does when the row exists and nothing changed). */
    private static Connection noRowsReturned() {
        ClassLoader cl = WorkItemArchiveRepositoryTest.class.getClassLoader();
        ResultSet empty = (ResultSet) Proxy.newProxyInstance(cl, new Class<?>[]{ResultSet.class},
                (p, m, a) -> m.getName().equals("next") ? Boolean.FALSE : defaultFor(m));
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(cl, new Class<?>[]{PreparedStatement.class},
                (p, m, a) -> m.getName().equals("executeQuery") ? empty : defaultFor(m));
        return (Connection) Proxy.newProxyInstance(cl, new Class<?>[]{Connection.class},
                (p, m, a) -> m.getName().equals("prepareStatement") ? ps : defaultFor(m));
    }

    private static Object defaultFor(Method method) {
        Class<?> r = method.getReturnType();
        if (r == boolean.class) return Boolean.FALSE;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        return null;
    }
}

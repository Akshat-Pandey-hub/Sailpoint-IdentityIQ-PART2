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
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_certification_archive. Golden fixture produced by Part 1's own NativeCertificationArchiveParser / Repository /
 * REST toJson. Covers the key rules (canonical / non-GUID fallback / refusal), the 10-field hash, the CEC lineage
 * envelope bindings, append-only SQL and the SKIPPED outcome, DDL.
 */
class CertificationArchiveRepositoryTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "certificationarchiveid", "source_id", "name", "certification_id", "certification_group_id", "creator_name",
            "owner_name", "comments", "signed", "expiration", "child_certification_ids", "archive_xml", "created_at",
            "modified_at", "archived_ts", "record_hash", "src_system", "src_object_type", "src_object_id",
            "src_natural_key", "src_created", "src_modified", "src_event_ts", "src_interface", "extraction_run_id",
            "raw_ref", "derived_at", "extracted_at");

    private static final Set<String> HASHED = Set.of("source_id", "certification_id", "certification_group_id",
            "creator_name", "owner_name", "comments", "signed", "child_certification_ids", "archive_xml", "created_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final CertificationArchiveRepository repo = new CertificationArchiveRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/certification-archive-part1-golden.txt");
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

    @Test
    void keyAndHashMatchPart1() throws Exception {
        List<Golden> gs = golden();
        for (Golden g : gs) {
            assertEquals(g.hash(), CertificationArchiveRepository.recordHash(g.rest()), "record_hash parity for " + g.rest());
            if (!"THROWS".equals(g.id())) {
                assertEquals(g.id(), CertificationArchiveRepository.id(g.rest()), "key parity");
            }
        }
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac3481ee", gs.get(0).id());
        assertEquals(CanonicalIds.deterministicUuid("native-certification-archive|arch-not-a-guid"), gs.get(1).id());
        assertEquals("THROWS", gs.get(2).id(), "Part 1 refuses a blank id");
        assertThrows(IllegalArgumentException.class, () -> CertificationArchiveRepository.id(gs.get(2).rest()));
    }

    @Test
    void hashCoversOnlyPart1sTenFields() throws Exception {
        Golden g = golden().get(0);
        List<String> fields = new ArrayList<>();
        g.rest().fieldNames().forEachRemaining(fields::add);
        for (String f : fields) {
            ObjectNode changed = g.rest().deepCopy();
            if (f.equals("signed") || f.equals("expiration") || f.endsWith("_at") || f.equals("archived_ts")) {
                changed.put(f, "2001-01-01T00:00:00Z");
            } else if (f.equals("child_certification_ids")) {
                changed.putArray(f).add("other");
            } else {
                changed.put(f, "changed-" + f);
            }
            if (HASHED.contains(f)) {
                assertNotEquals(g.hash(), CertificationArchiveRepository.recordHash(changed), f + " must be hashed");
            } else {
                assertEquals(g.hash(), CertificationArchiveRepository.recordHash(changed), f + " is NOT hashed in Part 1");
            }
        }
        assertEquals(14, fields.size(), "the response carries 14 fields");
    }

    @Test
    void bindsBusinessFieldsAndTheCecLineageEnvelopeLikePart1() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-73"));
        Map<Integer, Object> b = jdbc.executions.get(0);
        String created = g.rest().get("created_at").asText();
        String modified = g.rest().get("modified_at").asText();

        assertEquals(g.id(), b.get(1));
        assertEquals("7f0001019f061fdc819f34b0ac3481ee", b.get(2));
        assertEquals("7f0001019f061fdc819f34b0ac3471dd", b.get(4), "certification_id = the archived certification (raw id)");
        assertEquals("[\"7f0001019f061fdc819f34b0ac3471aa\",{\"n\":1.5}]", b.get(11), "child_certification_ids jsonb");
        assertTrue(((String) b.get(12)).startsWith("<?xml version='1.0'"), "archive_xml verbatim");
        assertEquals(created, b.get(13));
        assertEquals(modified, b.get(14));
        assertEquals(created, b.get(15), "archived_ts = created_at");
        assertEquals(g.hash(), b.get(16));
        assertEquals("IdentityIQ", b.get(17));
        assertEquals("sailpoint.object.CertificationArchive", b.get(18));
        assertEquals("7f0001019f061fdc819f34b0ac3481ee", b.get(19), "src_object_id = source_id");
        assertEquals(g.rest().get("name").asText(), b.get(20), "src_natural_key = archive name");
        assertEquals(created, b.get(21), "src_created");
        assertEquals(modified, b.get(22), "src_modified");
        assertEquals(created, b.get(23), "src_event_ts = archival time");
        assertEquals("native_iiq_java_api", b.get(24));
        assertEquals("run-73", b.get(25));
        assertNull(b.get(26), "raw_ref NULL");
        assertEquals(RecordingJdbc.NULL, b.get(27), "derived_at NULL");
        assertEquals(27, b.size());
    }

    @Test
    void existingRowsAreUpdatedOnlyWhenADataColumnChanged() throws Exception {
        String sql = repo.appendSql();
        assertTrue(sql.contains("ON CONFLICT (certificationarchiveid) DO UPDATE SET"));
        String where = sql.substring(sql.indexOf(" WHERE ROW("));
        assertTrue(where.contains(") IS DISTINCT FROM ROW("), "written only when a data column differs");
        assertFalse(where.contains("extraction_run_id"), "a new run alone never rewrites a row");
        assertTrue(sql.endsWith("RETURNING (xmax = 0)"));
        assertFalse(sql.contains("is_deleted"), "no soft-delete columns on this table");
        assertEquals(27, sql.chars().filter(ch -> ch == '?').count());

        // an unchanged existing row -> RETURNING yields no row -> SKIPPED
        assertEquals(EntityRepository.Outcome.SKIPPED, repo.upsert(noRowsReturned(), golden().get(0).rest(), "run-74"));
    }

    @Test
    void skippedRowsAreCountedAndOnlyShownWhenPresent() {
        ImportResult r = new ImportResult("CertificationArchive", "s.kf_certification_archive");
        r.countFetched(2);
        r.countSkipped();
        r.countSkipped();
        assertEquals(2, r.skipped());
        assertTrue(r.summary().contains("skipped:   2"));
        ImportResult other = new ImportResult("Identity", "s.kf_identity");
        assertFalse(other.summary().contains("skipped"), "other entities' summary is unchanged");
    }

    @Test
    void ddlMatchesIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("certificationarchiveid uuid PRIMARY KEY") && ddl.contains("child_certification_ids jsonb"));
        assertTrue(ddl.contains("archive_xml text"));
    }

    /** A connection whose INSERT … RETURNING yields no row (what PostgreSQL does when the row exists and nothing changed). */
    private static Connection noRowsReturned() {
        ResultSet empty = (ResultSet) Proxy.newProxyInstance(CertificationArchiveRepositoryTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (p, m, a) -> m.getName().equals("next") ? Boolean.FALSE : defaultFor(m));
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                CertificationArchiveRepositoryTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (p, m, a) -> m.getName().equals("executeQuery") ? empty : defaultFor(m));
        return (Connection) Proxy.newProxyInstance(CertificationArchiveRepositoryTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> m.getName().equals("prepareStatement") ? ps : defaultFor(m));
    }

    private static Object defaultFor(Method method) {
        Class<?> r = method.getReturnType();
        if (r == boolean.class) return Boolean.FALSE;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        return null;
    }
}

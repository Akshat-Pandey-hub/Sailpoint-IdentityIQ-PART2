package com.keyforge.part2.ingest;

import com.keyforge.part2.testsupport.ExpectedKey;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_access_hist_certification. The live endpoint and the iiq_native dump are both EMPTY, so the golden fixture was
 * produced by running Part 1's own NativeAccessHistoryCertificationRestService + NativeHistCertificationRepository over
 * plugin-shaped rows (a signed certification with a compact JSON cert payload, one whose payload is not JSON, a
 * non-GUID id, a null id with an empty-array payload). Covers key + fallback, the 8-field asText() hash, cert_json
 * restoration, bindings, append-only + DDL.
 */
class AccessHistoryCertificationRepositoryTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "hist_cert_id", "source_id", "name", "cert_id", "cert_type", "cert_name", "cert_display_name",
            "finished", "signed", "cert_json", "created_at", "modified_at", "record_hash", "source_system",
            "source_interface", "source_object_type", "extraction_run_id", "extracted_at");

    /** Response fields Part 1 hashes (8), by their response names. */
    private static final Set<String> HASHED = Set.of("source_id", "cert_id", "cert_type", "cert_name",
            "cert_display_name", "finished", "signed", "cert_json");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AccessHistoryCertificationRepository repo =
            new AccessHistoryCertificationRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String hash, String id, String storedCertJson) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/access-history-certification-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null, id = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) id = line.substring(3);
                else if (line.startsWith("STORED_CERT_JSON=")) {
                    String c = line.substring(17);
                    out.add(new Golden(mapper.readTree(rest), hash, id, c.equals("<NULL>") ? null : c));
                }
            }
        }
        assertEquals(4, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void keyAndHashMatchPart1() throws Exception {
        List<Golden> gs = golden();
        for (Golden g : gs) {
            assertEquals(ExpectedKey.of(g.id(), g.rest(), "native-hist-cert"), AccessHistoryCertificationRepository.id(g.rest()),
                    "hist_cert_id: IIQ id, else the row content");
            assertEquals(g.hash(), AccessHistoryCertificationRepository.recordHash(g.rest()), "record_hash parity");
        }
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac36c1d1", gs.get(0).id());
        assertEquals("7f000101-9f06-1fdc-819f-34b0ac36c2d2", gs.get(1).id(), "already-dashed id");
        assertEquals(CanonicalIds.deterministicUuid("native-hist-cert|hist-cert-not-a-guid"), gs.get(2).id());
        assertEquals(CanonicalIds.deterministicUuid("native-hist-cert|null"), gs.get(3).id(),
                "Java concatenation of a null id, as in Part 1");
    }

    @Test
    void hashCoversExactlyPart1sEightFields() throws Exception {
        Golden g = golden().get(0);
        List<String> fields = new ArrayList<>();
        g.rest().fieldNames().forEachRemaining(fields::add);
        assertEquals(11, fields.size(), "the response carries 11 fields");
        for (String f : fields) {
            ObjectNode changed = g.rest().deepCopy();
            if (f.equals("cert_json")) {
                changed.putObject(f).put("other", 1);
            } else if (f.equals("finished") || f.equals("signed") || f.endsWith("_at")) {
                changed.put(f, "2001-01-01T00:00:00Z");
            } else {
                changed.put(f, "changed-" + f);
            }
            if (HASHED.contains(f)) {
                assertNotEquals(g.hash(), AccessHistoryCertificationRepository.recordHash(changed), f + " must be hashed");
            } else {
                assertEquals(g.hash(), AccessHistoryCertificationRepository.recordHash(changed),
                        f + " is NOT hashed in Part 1");
            }
        }
    }

    @Test
    void bindsCertJsonVerbatimAndLineage() throws Exception {
        for (Golden g : golden()) {
            RecordingJdbc jdbc = new RecordingJdbc();
            assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-hc"));
            Map<Integer, Object> b = jdbc.executions.get(0);
            assertEquals(ExpectedKey.of(g.id(), g.rest(), "native-hist-cert"), b.get(1));
            assertEquals(g.storedCertJson(), b.get(10), "cert_json text exactly as Part 1 stores it");
            assertEquals(g.hash(), b.get(13));
            assertEquals("IdentityIQ", b.get(14));
            assertEquals("native_iiq_java_api", b.get(15));
            assertEquals("sailpoint.object.accesshistory.HistoricalCertification", b.get(16));
            assertEquals("run-hc", b.get(17));
            assertEquals(17, b.size());
        }
        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), golden().get(0).rest(), "run-hc");
        Map<Integer, Object> b = jdbc.executions.get(0);
        assertEquals("7f0001019f061fdc819f34b0ac3471dd", b.get(4), "cert_id = the IIQ certification (raw id)");
        assertEquals("2026-10-02T15:24:00.100Z", b.get(8), "finished");
        assertEquals("2026-10-02T15:23:51.988Z", b.get(9), "signed");
    }

    @Test
    void existingRowsAreUpdatedOnlyWhenADataColumnChanged() throws Exception {
        String sql = repo.appendSql();
        assertTrue(sql.contains("ON CONFLICT (hist_cert_id) DO UPDATE SET"));
        String where = sql.substring(sql.indexOf(" WHERE ROW("));
        assertTrue(where.contains(") IS DISTINCT FROM ROW("), "written only when a data column differs");
        assertFalse(where.contains("extraction_run_id"), "a new run alone never rewrites a row");
        assertTrue(sql.endsWith("RETURNING (xmax = 0)"));
        assertEquals(17, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(EntityRepository.Outcome.SKIPPED, repo.upsert(noRowsReturned(), golden().get(0).rest(), "run-hc2"));
    }

    @Test
    void ddlMatchesIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("cert_json text") && ddl.contains("signed timestamp with time zone"));
        assertFalse(ddl.contains("is_deleted"), "append-only, no soft delete");
        assertEquals("access-history/certifications", repo.resource());
    }

    /** A connection whose INSERT … RETURNING yields no row (what PostgreSQL does when the row exists and nothing changed). */
    private static Connection noRowsReturned() {
        ClassLoader cl = AccessHistoryCertificationRepositoryTest.class.getClassLoader();
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

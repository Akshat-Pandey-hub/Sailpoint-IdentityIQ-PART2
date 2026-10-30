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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_access_hist_entitlement. Golden fixture = real live {@code /kfagent/access-history} rows paired with the
 * hist_capture_id, record_hash and capture_json text Part 1 stored for them in iiq_native (a role-granted capture, a
 * deletion capture with a pending request item, a patch capture whose capture_json is an array, a request-item
 * capture). Covers key + fallback, Part 1's asText()-based 20-field hash (attributes content NOT hashed), capture_json
 * restoration, empty-jsonb → NULL, column order with the 4 trailing columns, append-only SQL + SKIPPED.
 */
class AccessHistoryEntitlementRepositoryTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "hist_capture_id", "source_id", "entity_id", "entity_name", "identity_id", "identity_name",
            "identity_entitlement_id", "application_id", "application_name", "native_identity", "instance",
            "display_value", "attribute_name", "attribute_value", "type", "granted_by_role", "role_id",
            "request_item_id", "pending_request_item_id", "certification_item_id", "deleted", "effective_date",
            "extended_to_date", "latest", "compressed", "brief", "full_capture", "patch", "smart_hash", "full_hash",
            "json_format", "transform_type", "patch_doc_parent", "capture_json", "attributes", "extended_attributes",
            "created_at", "modified_at", "record_hash", "source_system", "source_interface", "source_object_type",
            "extraction_run_id", "extracted_at", "name", "compressed_property_flag", "compressed_property_flag_value",
            "compressible_property_names");

    /** Response fields Part 1 hashes (20), by their response names. */
    private static final Set<String> HASHED = Set.of("source_id", "entity_id", "identity_id",
            "identity_entitlement_id", "application_id", "attribute_name", "attribute_value", "type",
            "granted_by_role", "role_id", "request_item_id", "certification_item_id", "deleted", "effective_date",
            "latest", "smart_hash", "full_hash", "capture_json", "attributes", "extended_attributes");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AccessHistoryEntitlementRepository repo = new AccessHistoryEntitlementRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String hash, String id, String storedCaptureJson) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/access-history-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null, id = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) id = line.substring(3);
                else if (line.startsWith("STORED_CAPTURE_JSON=")) {
                    out.add(new Golden(mapper.readTree(rest), hash, id, line.substring(20)));
                }
            }
        }
        assertEquals(4, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void keyAndHashMatchPart1StoredRows() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.id(), AccessHistoryEntitlementRepository.id(g.rest()), "hist_capture_id parity");
            assertEquals(g.hash(), AccessHistoryEntitlementRepository.recordHash(g.rest()), "record_hash parity");
        }
        assertEquals("7f000101-9fbf-1a17-819f-ca120e4b13a5", golden().get(0).id(), "canonical form of source_id");
    }

    @Test
    void fallbackKeys() throws Exception {
        ObjectNode r = golden().get(0).rest().deepCopy();
        r.put("source_id", "not-a-guid");
        assertEquals(CanonicalIds.deterministicUuid("native-hist-ent|not-a-guid"), AccessHistoryEntitlementRepository.id(r));
        r.putNull("source_id");
        assertEquals(CanonicalIds.contentUuid("native-hist-ent", r), AccessHistoryEntitlementRepository.id(r),
                "no IIQ id: a uuid from the row content, never a shared \"null\" key");
    }

    @Test
    void hashCoversExactlyPart1sTwentyFields() throws Exception {
        Golden g = golden().get(1);
        List<String> fields = new ArrayList<>();
        g.rest().fieldNames().forEachRemaining(fields::add);
        assertEquals(41, fields.size(), "the response carries 41 fields");
        for (String f : fields) {
            if (f.equals("attributes") || f.equals("extended_attributes")) {
                continue; // see attributeContentIsNotHashed
            }
            ObjectNode changed = g.rest().deepCopy();
            JsonNode v = g.rest().get(f);
            if (v.isBoolean()) {
                changed.put(f, !v.asBoolean());
            } else if (f.equals("capture_json") || f.equals("compressible_property_names")) {
                changed.putArray(f).add("other");
            } else if (f.endsWith("_date") || f.endsWith("_at")) {
                changed.put(f, "2001-01-01T00:00:00Z");
            } else {
                changed.put(f, "changed-" + f);
            }
            if (HASHED.contains(f)) {
                assertNotEquals(g.hash(), AccessHistoryEntitlementRepository.recordHash(changed), f + " must be hashed");
            } else {
                assertEquals(g.hash(), AccessHistoryEntitlementRepository.recordHash(changed), f + " is NOT hashed in Part 1");
            }
        }
    }

    /** Part 1 hashes attributes / extendedAttributes with asText(), which is "" for any JSON object. */
    @Test
    void attributeContentIsNotHashedButPresenceIs() throws Exception {
        Golden g = golden().get(0);
        ObjectNode changed = g.rest().deepCopy();
        changed.putObject("attributes").put("anything", "else");
        changed.putObject("extended_attributes").put("x", 1);
        assertEquals(g.hash(), AccessHistoryEntitlementRepository.recordHash(changed), "object content is not hashed");
        ObjectNode absent = g.rest().deepCopy();
        absent.putNull("attributes");
        assertNotEquals(g.hash(), AccessHistoryEntitlementRepository.recordHash(absent), "null differs from an object");
    }

    @Test
    void bindsCaptureJsonAndJsonbVerbatimAndTrailingColumns() throws Exception {
        for (Golden g : golden()) {
            RecordingJdbc jdbc = new RecordingJdbc();
            assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-ah"));
            Map<Integer, Object> b = jdbc.executions.get(0);
            assertEquals(g.id(), b.get(1));
            assertEquals(g.storedCaptureJson(), b.get(34), "capture_json text exactly as Part 1 stored it");
            assertEquals(jsonOrNull(g.rest(), "attributes"), b.get(35), "attributes exactly as received ({} stays {})");
            assertEquals(jsonOrNull(g.rest(), "extended_attributes"), b.get(36), "extended_attributes exactly as received");
            assertEquals(g.hash(), b.get(39));
            assertEquals("IdentityIQ", b.get(40));
            assertEquals("native_iiq_java_api", b.get(41));
            assertEquals("sailpoint.object.accesshistory.HistoricalEntitlementCapture", b.get(42));
            assertEquals("run-ah", b.get(43));
            assertEquals("[\"json\"]", b.get(47), "compressible_property_names after extracted_at");
            assertEquals(47, b.size());
        }
        Golden patch = golden().get(2);
        assertTrue(patch.rest().get("patch").asBoolean() && patch.rest().get("capture_json").isArray());
        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), patch.rest(), "run-ah");
        assertEquals(Boolean.TRUE, jdbc.executions.get(0).get(28), "patch boolean");
        assertTrue(((String) jdbc.executions.get(0).get(34)).startsWith("["), "patch documents are a JSON array");
    }

    @Test
    void existingRowsAreUpdatedOnlyWhenADataColumnChanged() throws Exception {
        String sql = repo.appendSql();
        assertTrue(sql.contains("ON CONFLICT (hist_capture_id) DO UPDATE SET"));
        String where = sql.substring(sql.indexOf(" WHERE ROW("));
        assertTrue(where.contains(") IS DISTINCT FROM ROW("), "written only when a data column differs");
        assertFalse(where.contains("extraction_run_id"), "a new run alone never rewrites a row");
        assertTrue(sql.endsWith("RETURNING (xmax = 0)"));
        assertEquals(47, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(EntityRepository.Outcome.SKIPPED, repo.upsert(noRowsReturned(), golden().get(0).rest(), "run-ah2"));
    }

    @Test
    void ddlMatchesIiqNativeIncludingTrailingColumns() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("capture_json text") && ddl.contains("attributes jsonb")
                && ddl.contains("full_capture boolean") && ddl.contains("compressible_property_names jsonb"));
        assertFalse(ddl.contains("is_deleted"), "append-only, no soft delete");
        assertEquals("access-history", repo.resource());
    }

    /** A connection whose INSERT … RETURNING yields no row (what PostgreSQL does when the row exists and nothing changed). */
    private static Connection noRowsReturned() {
        ClassLoader cl = AccessHistoryEntitlementRepositoryTest.class.getClassLoader();
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

    /** The value as Part 1 sent it, as compact JSON, or null. */
    private static String jsonOrNull(com.fasterxml.jackson.databind.JsonNode row, String field) {
        com.fasterxml.jackson.databind.JsonNode n = row.get(field);
        return n == null || n.isNull() ? null : n.toString();
    }
}

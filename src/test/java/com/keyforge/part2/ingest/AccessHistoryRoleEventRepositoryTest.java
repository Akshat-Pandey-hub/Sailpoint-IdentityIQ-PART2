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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_access_hist_role_event. Golden fixture = ALL 10 live {@code /kfagent/access-history/role-events} rows paired with
 * the hist_event_id, record_hash and event_detail_json text Part 1 stored for them in iiq_native (RoleDiscovered without
 * detail, RoleChanged for type / provisioningForms / sysDescriptions / selector script / applications / supers).
 * Covers the role-event key salt, the shared 17-field hash, event_detail_json restoration, bindings, append-only + DDL.
 */
class AccessHistoryRoleEventRepositoryTest {

    /** iiq_native.kf_access_hist_role_event has exactly the identity-event column layout. */
    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "hist_event_id", "source_id", "name", "entity_id", "entity_name", "defined_entity_name", "event_type",
            "event_category", "event_source_type", "event_date", "event_detail_json", "prev_capture_id", "capture_id",
            "audit_event_id", "property_name", "old_value", "new_value", "account_id", "acct_app_id", "acct_app_name",
            "acct_app_instance", "acct_display_name", "acct_native_id", "pending_request_item_id", "request_item_id",
            "identity_request_id", "identity_entitlement_id", "qry_property1", "qry_property2", "qry_property3",
            "qry_property4", "qry_property5", "qry_property6", "qry_property7", "qry_property8", "qry_property9",
            "qry_property10", "created_at", "modified_at", "record_hash", "source_system", "source_interface",
            "source_object_type", "extraction_run_id", "extracted_at");

    private static final Set<String> HASHED = Set.of("source_id", "entity_id", "event_type", "event_category",
            "event_source_type", "event_date", "capture_id", "prev_capture_id", "property_name", "old_value",
            "new_value", "account_id", "acct_app_id", "request_item_id", "identity_request_id",
            "identity_entitlement_id", "event_detail_json");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AccessHistoryRoleEventRepository repo = new AccessHistoryRoleEventRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String hash, String id, String storedDetail) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/access-history-role-event-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null, id = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) id = line.substring(3);
                else if (line.startsWith("STORED_EVENT_DETAIL_JSON=")) {
                    String d = line.substring(25);
                    out.add(new Golden(mapper.readTree(rest), hash, id,
                            d.equals("<NULL>") ? null : mapper.readValue(d, String.class)));
                }
            }
        }
        assertEquals(10, out.size(), "golden fixture rows");
        return out;
    }

    private Golden byProperty(String property) throws Exception {
        for (Golden g : golden()) {
            if (property.equals(g.rest().path("property_name").asText(null))) {
                return g;
            }
        }
        throw new IllegalArgumentException(property);
    }

    @Test
    void keyAndHashMatchAllPart1StoredRows() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.id(), AccessHistoryRoleEventRepository.id(g.rest()), "hist_event_id parity");
            assertEquals(g.hash(), AccessHistoryRoleEventRepository.recordHash(g.rest()), "record_hash parity");
        }
    }

    @Test
    void fallbackKeyUsesTheRoleEventSalt() throws Exception {
        ObjectNode r = golden().get(0).rest().deepCopy();
        r.put("source_id", "not-a-guid");
        assertEquals(CanonicalIds.deterministicUuid("native-hist-role-event|not-a-guid"),
                AccessHistoryRoleEventRepository.id(r));
        assertNotEquals(AccessHistoryIdentityEventRepository.id(r), AccessHistoryRoleEventRepository.id(r),
                "role events are salted differently from identity events");
        r.putNull("source_id");
        assertEquals(CanonicalIds.contentUuid("native-hist-role-event", r), AccessHistoryRoleEventRepository.id(r),
                "no IIQ id: a uuid from the row content");
    }

    @Test
    void hashCoversExactlyPart1sSeventeenFields() throws Exception {
        Golden g = byProperty("type");
        List<String> fields = new ArrayList<>();
        g.rest().fieldNames().forEachRemaining(fields::add);
        assertEquals(38, fields.size(), "the response carries 38 fields");
        for (String f : fields) {
            ObjectNode changed = g.rest().deepCopy();
            if (f.equals("event_detail_json")) {
                changed.putObject(f).put("other", 1);
            } else if (f.equals("event_date") || f.endsWith("_at")) {
                changed.put(f, "2001-01-01T00:00:00Z");
            } else {
                changed.put(f, "changed-" + f);
            }
            if (HASHED.contains(f)) {
                assertNotEquals(g.hash(), AccessHistoryRoleEventRepository.recordHash(changed), f + " must be hashed");
            } else {
                assertEquals(g.hash(), AccessHistoryRoleEventRepository.recordHash(changed), f + " is NOT hashed in Part 1");
            }
        }
    }

    @Test
    void bindsEventDetailVerbatimAndRoleLineage() throws Exception {
        for (Golden g : golden()) {
            RecordingJdbc jdbc = new RecordingJdbc();
            assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-hre"));
            Map<Integer, Object> b = jdbc.executions.get(0);
            assertEquals(g.id(), b.get(1));
            assertEquals(g.storedDetail(), b.get(11), "event_detail_json text exactly as Part 1 stored it");
            assertEquals(g.hash(), b.get(40));
            assertEquals("IdentityIQ", b.get(41));
            assertEquals("native_iiq_java_api", b.get(42));
            assertEquals("sailpoint.object.accesshistory.HistoricalRoleEvent", b.get(43));
            assertEquals("run-hre", b.get(44));
            assertEquals(44, b.size());
        }
        Golden selector = byProperty("selector");
        assertTrue(selector.storedDetail().contains("\\n"), "script newlines stay JSON-escaped in the stored text");
        Golden type = byProperty("type");
        RecordingJdbc jdbc = new RecordingJdbc();
        repo.upsert(jdbc.connection(), type.rest(), "run-hre");
        assertEquals("Engineering-Base", jdbc.executions.get(0).get(5), "entity_name = the role");
        assertEquals("organizational", jdbc.executions.get(0).get(16));
        assertEquals("business", jdbc.executions.get(0).get(17));
    }

    @Test
    void existingRowsAreUpdatedOnlyWhenADataColumnChanged() throws Exception {
        String sql = repo.appendSql();
        assertTrue(sql.contains("INSERT INTO iiq_native_part2_test.kf_access_hist_role_event AS cur ("));
        assertTrue(sql.contains("ON CONFLICT (hist_event_id) DO UPDATE SET"));
        String where = sql.substring(sql.indexOf(" WHERE ROW("));
        assertTrue(where.contains(") IS DISTINCT FROM ROW("), "written only when a data column differs");
        assertFalse(where.contains("extraction_run_id"), "a new run alone never rewrites a row");
        assertTrue(sql.endsWith("RETURNING (xmax = 0)"));
        assertEquals(44, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(EntityRepository.Outcome.SKIPPED, repo.upsert(noRowsReturned(), golden().get(0).rest(), "run-hre2"));
    }

    @Test
    void ddlMatchesIiqNative() {
        String ddl = repo.createTableSql();
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS iiq_native_part2_test.kf_access_hist_role_event ("));
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("event_detail_json text") && ddl.contains("event_date timestamp with time zone"));
        assertFalse(ddl.contains("is_deleted"), "append-only, no soft delete");
        assertEquals("access-history/role-events", repo.resource());
    }

    /** A connection whose INSERT … RETURNING yields no row (what PostgreSQL does when the row exists and nothing changed). */
    private static Connection noRowsReturned() {
        ClassLoader cl = AccessHistoryRoleEventRepositoryTest.class.getClassLoader();
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

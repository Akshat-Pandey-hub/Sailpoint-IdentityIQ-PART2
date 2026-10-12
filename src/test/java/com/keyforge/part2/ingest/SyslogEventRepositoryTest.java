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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kf_syslog_event. Golden fixture = real live {@code /kfagent/syslog-events} rows paired with the syslogid and
 * record_hash Part 1 stored for them in iiq_native (the same formula reproduced all 122,556 stored rows). Covers key +
 * fallbacks, the 10-field hash (classname NOT hashed), bindings + lineage, append-only SQL + SKIPPED, DDL order.
 */
class SyslogEventRepositoryTest {

    /** Part 1's current CREATE TABLE order (classname between thread and line_number). */
    private static final List<String> COLUMNS = List.of(
            "syslogid", "source_id", "quick_key", "event_level", "server", "username", "thread", "classname",
            "line_number", "message", "stacktrace", "created_at", "record_hash", "source_system", "source_interface",
            "source_object_type", "extraction_run_id", "extracted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final SyslogEventRepository repo = new SyslogEventRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/syslog-event-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), hash, line.substring(3)));
            }
        }
        assertEquals(2, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void keyAndHashMatchPart1StoredRows() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.id(), SyslogEventRepository.id(g.rest()), "syslogid parity");
            assertEquals(g.hash(), SyslogEventRepository.recordHash(g.rest()), "record_hash parity");
        }
        assertEquals("7f000101-97e1-19c4-8197-e1a9de110000", golden().get(0).id(), "canonical form of source_id");
    }

    @Test
    void fallbackKeysFollowPart1() throws Exception {
        ObjectNode r = golden().get(0).rest().deepCopy();
        r.put("source_id", "not-a-guid");
        assertEquals(CanonicalIds.deterministicUuid("native-syslog|not-a-guid"), SyslogEventRepository.id(r));
        r.putNull("source_id");
        assertEquals(CanonicalIds.deterministicUuid("native-syslog|0000000001"), SyslogEventRepository.id(r),
                "null source_id falls back to quick_key");
    }

    @Test
    void hashCoversTenFieldsButNotClassname() throws Exception {
        Golden g = golden().get(1);
        List<String> fields = new ArrayList<>();
        g.rest().fieldNames().forEachRemaining(fields::add);
        assertEquals(11, fields.size(), "the response carries 11 fields");
        for (String f : fields) {
            ObjectNode changed = g.rest().deepCopy();
            changed.put(f, f.equals("created_at") ? "2001-01-01T00:00:00Z" : "changed-" + f);
            if (f.equals("classname")) {
                assertEquals(g.hash(), SyslogEventRepository.recordHash(changed), "classname is NOT hashed in Part 1");
            } else {
                assertNotEquals(g.hash(), SyslogEventRepository.recordHash(changed), f + " must be hashed");
            }
        }
    }

    @Test
    void bindsFieldsAndLineageLikePart1() throws Exception {
        Golden g = golden().get(0);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-sl"));
        Map<Integer, Object> b = jdbc.executions.get(0);
        assertEquals(g.id(), b.get(1));
        assertEquals("7f00010197e119c48197e1a9de110000", b.get(2));
        assertEquals("0000000001", b.get(3));
        assertEquals("ERROR", b.get(4));
        assertEquals("sailpoint.messagebus.impl.ActiveMQMessageServiceManager", b.get(8), "classname stored");
        assertEquals(g.rest().get("created_at").asText(), b.get(12));
        assertEquals(g.hash(), b.get(13));
        assertEquals("IdentityIQ", b.get(14));
        assertEquals("native_iiq_java_api", b.get(15));
        assertEquals("sailpoint.object.SyslogEvent", b.get(16));
        assertEquals("run-sl", b.get(17));
        assertEquals(17, b.size());

        ObjectNode noTrace = g.rest().deepCopy();
        noTrace.putNull("stacktrace");
        RecordingJdbc j2 = new RecordingJdbc();
        repo.upsert(j2.connection(), noTrace, "run-sl");
        assertNull(j2.executions.get(0).get(11), "null stacktrace -> NULL");
    }

    @Test
    void appendOnlySqlNeverUpdatesAndReportsExistingRowsAsSkipped() throws Exception {
        String sql = repo.appendSql();
        assertTrue(sql.contains("ON CONFLICT (syslogid) DO NOTHING RETURNING syslogid"));
        assertFalse(sql.contains("DO UPDATE"), "syslog events are never updated");
        assertEquals(17, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(EntityRepository.Outcome.SKIPPED, repo.upsert(noRowsReturned(), golden().get(0).rest(), "run-sl2"));
    }

    @Test
    void ddlMatchesPart1() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(COLUMNS, cols);
        assertTrue(ddl.contains("syslogid uuid PRIMARY KEY") && ddl.contains("created_at timestamp with time zone"));
        assertFalse(ddl.contains("is_deleted"), "append-only, no soft delete");
        assertEquals("syslog-events", repo.resource());
    }

    /** A connection whose INSERT … RETURNING yields no row (what PostgreSQL does for ON CONFLICT DO NOTHING). */
    private static Connection noRowsReturned() {
        ClassLoader cl = SyslogEventRepositoryTest.class.getClassLoader();
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

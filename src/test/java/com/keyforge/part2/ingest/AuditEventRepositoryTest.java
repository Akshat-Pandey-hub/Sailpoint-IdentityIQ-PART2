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
 * kf_audit_event. Golden fixture = real live {@code /kfagent/audit-events} rows paired with the auditid and record_hash
 * Part 1 stored for them in iiq_native.kf_audit_event (empty attributes, nested attributes with nulls, null target).
 * Covers key + fallback rules, the 19-field hash, bindings + lineage, append-only SQL with the SKIPPED outcome, DDL.
 */
class AuditEventRepositoryTest {

    private static final List<String> IIQ_NATIVE_COLUMNS = List.of(
            "auditid", "source_id", "action", "audit_source", "target", "application", "account_name", "instance",
            "attribute_name", "attribute_value", "interface_name", "server_host", "client_host", "tracking_id",
            "string1", "string2", "string3", "string4", "attributes", "created_at", "record_hash", "source_system",
            "source_interface", "source_object_type", "extraction_run_id", "extracted_at");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuditEventRepository repo = new AuditEventRepository("iiq_native_part2_test");

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/audit-event-part1-golden.txt");
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
    void keyAndHashMatchPart1StoredRows() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.id(), AuditEventRepository.id(g.rest()), "auditid parity for " + g.rest());
            assertEquals(g.hash(), AuditEventRepository.recordHash(g.rest()), "record_hash parity for " + g.rest());
        }
        assertEquals("7f000101-9eb9-1d3c-819f-06cf65c20836", golden().get(0).id(), "canonical form of source_id");
    }

    @Test
    void fallbackKeys() throws Exception {
        ObjectNode r = (ObjectNode) golden().get(0).rest().deepCopy();
        r.put("source_id", "not-a-guid");
        assertEquals(CanonicalIds.deterministicUuid("native-audit|not-a-guid"), AuditEventRepository.id(r));
        r.putNull("source_id");
        assertEquals(CanonicalIds.contentUuid("native-audit", r), AuditEventRepository.id(r),
                "no IIQ id: a uuid from the row content, never the action name");
        ObjectNode other = r.deepCopy();
        other.put("target", "another target");
        assertNotEquals(AuditEventRepository.id(r), AuditEventRepository.id(other),
                "two different events with the same action never share a uuid");
    }

    @Test
    void hashCoversAllNineteenFields() throws Exception {
        Golden g = golden().get(1);
        List<String> fields = new ArrayList<>();
        g.rest().fieldNames().forEachRemaining(fields::add);
        assertEquals(19, fields.size(), "the response carries 19 fields");
        for (String f : fields) {
            ObjectNode changed = g.rest().deepCopy();
            if (f.equals("created_at")) {
                changed.put(f, "2001-01-01T00:00:00Z");
            } else if (f.equals("attributes")) {
                changed.putObject(f).put("other", 1);
            } else {
                changed.put(f, "changed-" + f);
            }
            assertNotEquals(g.hash(), AuditEventRepository.recordHash(changed), f + " must be hashed");
        }
    }

    @Test
    void bindsFieldsAndLineageLikePart1() throws Exception {
        Golden g = golden().get(1);
        RecordingJdbc jdbc = new RecordingJdbc();
        assertEquals(EntityRepository.Outcome.INSERTED, repo.upsert(jdbc.connection(), g.rest(), "run-ae"));
        Map<Integer, Object> b = jdbc.executions.get(0);

        assertEquals(g.id(), b.get(1));
        assertEquals("7f0001019f061fdc819f06e6cb170029", b.get(2));
        assertEquals("AccessRequestStart", b.get(3));
        assertEquals("spadmin", b.get(4), "audit_source raw name");
        assertNull(b.get(8), "instance NULL");
        assertEquals(g.rest().get("attributes").toString(), b.get(19), "attributes compact JSON for jsonb");
        assertTrue(((String) b.get(19)).contains("\"attachments\":null"), "nested nulls kept");
        assertEquals(g.rest().get("created_at").asText(), b.get(20));
        assertEquals(g.hash(), b.get(21));
        assertEquals("IdentityIQ", b.get(22));
        assertEquals("native_iiq_java_api", b.get(23));
        assertEquals("sailpoint.object.AuditEvent", b.get(24));
        assertEquals("run-ae", b.get(25));
        assertEquals(25, b.size());
    }

    @Test
    void appendOnlySqlNeverUpdatesAndReportsExistingRowsAsSkipped() throws Exception {
        String sql = repo.appendSql();
        assertTrue(sql.contains("ON CONFLICT (auditid) DO NOTHING RETURNING auditid"));
        assertFalse(sql.contains("DO UPDATE"), "audit events are never updated");
        assertTrue(sql.contains("?::jsonb, ?::timestamptz"));
        assertEquals(25, sql.chars().filter(ch -> ch == '?').count());
        assertEquals(EntityRepository.Outcome.SKIPPED, repo.upsert(noRowsReturned(), golden().get(0).rest(), "run-ae2"));
    }

    @Test
    void ddlMatchesIiqNative() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        assertEquals(IIQ_NATIVE_COLUMNS, cols);
        assertTrue(ddl.contains("auditid uuid PRIMARY KEY") && ddl.contains("attributes jsonb")
                && ddl.contains("created_at timestamp with time zone"));
        assertFalse(ddl.contains("is_deleted"), "append-only, no soft delete");
    }

    /** A connection whose INSERT … RETURNING yields no row (what PostgreSQL does for ON CONFLICT DO NOTHING). */
    private static Connection noRowsReturned() {
        ClassLoader cl = AuditEventRepositoryTest.class.getClassLoader();
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

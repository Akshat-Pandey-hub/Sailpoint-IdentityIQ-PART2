package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.id.CanonicalIds;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Exercises the real {@link IdentityRepository#upsert} against a recording JDBC stub (java.lang.reflect
 * Proxy) — no live database — to verify the response→column mapping: canonical {@code userid}, text/boolean
 * passthrough, nested JSON serialized for {@code jsonb}, ISO timestamps passed through, SQL NULL for
 * missing/null fields, and the generated metadata parameters.
 */
class IdentityBindingTest {

    /** Sentinel for a bound SQL NULL (so we can distinguish it from a bound Java null string). */
    private static final Object NULL = new Object();

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode sampleIdentity() throws Exception {
        String json = "{"
                + "\"source_id\":\"7f0001019714176881971476c14808ec\","
                + "\"name\":\"Alice.Admin\","
                + "\"display_name\":null,"                                  // null text
                + "\"email\":\"alice@corp.com\","
                + "\"inactive\":false,"                                     // boolean
                + "\"correlated\":true,"
                + "\"manager_id\":null,"                                    // null text
                + "\"accounts\":[{\"app\":\"AD\",\"id\":\"a1\"}],"          // nested json array
                + "\"attributes\":{\"dept\":\"IT\",\"region\":null},"       // nested json object
                + "\"last_login\":\"2026-05-29T10:00:00Z\","                // timestamp
                + "\"created_at\":null"                                     // null timestamp
                + "}";
        return mapper.readTree(json);
    }

    @Test
    void bindsCanonicalUseridBusinessFieldsMetadataAndNulls() throws Exception {
        Map<Integer, Object> bound = new LinkedHashMap<>();
        Connection conn = recordingConnection(bound);

        IdentityRepository repo = new IdentityRepository("iiq_native_part2");
        IdentityRepository.Outcome outcome = repo.upsert(conn, sampleIdentity(), "run-123");
        assertEquals(IdentityRepository.Outcome.INSERTED, outcome, "stub ResultSet reports xmax=0 -> inserted");

        // index 1 = userid = canonicalUuid(source_id)
        assertEquals("7f000101-9714-1768-8197-1476c14808ec", bound.get(1));
        assertEquals(CanonicalIds.identityUserid("7f0001019714176881971476c14808ec", "Alice.Admin"), bound.get(1));

        // business columns (index = 2 + position in BUSINESS)
        assertEquals("7f0001019714176881971476c14808ec", bound.get(idx("source_id")), "source_id kept verbatim");
        assertEquals("Alice.Admin", bound.get(idx("name")));
        assertSame(NULL, bound.get(idx("display_name")), "null text -> SQL NULL");
        assertEquals("alice@corp.com", bound.get(idx("email")));
        assertEquals(Boolean.FALSE, bound.get(idx("inactive")), "boolean bound as boolean");
        assertEquals(Boolean.TRUE, bound.get(idx("correlated")));
        assertSame(NULL, bound.get(idx("manager_id")), "null text -> SQL NULL");
        // nested JSON serialized verbatim for jsonb
        assertEquals("[{\"app\":\"AD\",\"id\":\"a1\"}]", bound.get(idx("accounts")));
        assertEquals("{\"dept\":\"IT\",\"region\":null}", bound.get(idx("attributes")));
        assertEquals("2026-05-29T10:00:00Z", bound.get(idx("last_login")), "ISO timestamp passed through");
        assertSame(NULL, bound.get(idx("created_at")), "null timestamp -> SQL NULL");
        // a field entirely absent from the JSON -> SQL NULL (e.g. score)
        assertSame(NULL, bound.get(idx("score")), "absent field -> SQL NULL");

        // metadata params come right after the business columns, in order
        int metaBase = 2 + IdentityTable.BUSINESS.size();
        assertEquals("IdentityIQ", bound.get(metaBase));
        assertEquals("native_iiq_java_api", bound.get(metaBase + 1));
        assertEquals("sailpoint.object.Identity", bound.get(metaBase + 2));
        assertEquals("run-123", bound.get(metaBase + 3));
    }

    private static int idx(String column) {
        for (int i = 0; i < IdentityTable.BUSINESS.size(); i++) {
            if (IdentityTable.BUSINESS.get(i).name.equals(column)) {
                return 2 + i;
            }
        }
        throw new IllegalArgumentException("no such business column: " + column);
    }

    // ── recording JDBC stubs via dynamic proxy ──

    private static Connection recordingConnection(Map<Integer, Object> bound) {
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                IdentityBindingTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                new PsHandler(bound));
        return (Connection) Proxy.newProxyInstance(
                IdentityBindingTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> "prepareStatement".equals(method.getName()) ? ps : defaultFor(method));
    }

    private static final class PsHandler implements InvocationHandler {
        private final Map<Integer, Object> bound;
        PsHandler(Map<Integer, Object> bound) { this.bound = bound; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "setString" -> bound.put((Integer) args[0], args[1]);
                case "setBoolean" -> bound.put((Integer) args[0], args[1]);
                case "setNull" -> bound.put((Integer) args[0], NULL);
                case "executeQuery" -> {
                    return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{ResultSet.class}, new RsHandler());
                }
                default -> { /* close(), etc. */ }
            }
            return defaultFor(method);
        }
    }

    /** One-row result whose single column is TRUE (simulates RETURNING xmax=0 -> inserted). */
    private static final class RsHandler implements InvocationHandler {
        private boolean advanced = false;
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "next" -> {
                    boolean first = !advanced;
                    advanced = true;
                    return first;
                }
                case "getBoolean" -> { return Boolean.TRUE; }
                default -> { return defaultFor(method); }
            }
        }
    }

    private static Object defaultFor(Method method) {
        Class<?> r = method.getReturnType();
        if (r == boolean.class) return Boolean.FALSE;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        return null;
    }
}

package com.keyforge.part2.incremental;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.http.KfAgentClient;
import com.keyforge.part2.ingest.EntityRepository;
import com.keyforge.part2.ingest.EntityWriter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FamilyIncrementalImportService}: a parent and its children share ONE bound and ONE watermark; each dataset is
 * paged and written page by page; the watermark advances only when the parent and every child loaded with no failure,
 * so a failed child can never be skipped by a later run.
 */
class FamilyIncrementalImportServiceTest {

    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-05T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-09T10:00:00Z");
    private static final Duration OVERLAP = Duration.ofMinutes(5);
    private static final String KEY = "native:kf_identity_request+kf_identity_request_item+kf_identity_request_approval";

    /** Parent change times (for the coalesce max) + the watermark table, behind JDBC proxies. */
    private static final class Db {
        final Map<String, Instant> parentChanged = new LinkedHashMap<>();
        final Map<String, Object[]> watermarks = new LinkedHashMap<>(); // key -> {field, instant, runId}
        final List<String> maxSql = new ArrayList<>();

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "createStatement" -> statement();
                        case "prepareStatement" -> prepared();
                        default -> defaultFor(m);
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                    (p, m, a) -> {
                        if (m.getName().equals("executeQuery")) {
                            maxSql.add((String) a[0]);
                            Instant max = parentChanged.values().stream().max(Instant::compareTo).orElse(null);
                            return result(max == null ? null : max.atOffset(ZoneOffset.UTC));
                        }
                        return defaultFor(m);
                    });
        }

        private PreparedStatement prepared() {
            Map<Integer, Object> bound = new LinkedHashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "setString", "setObject" -> {
                                bound.put((Integer) a[0], a[1]);
                                return null;
                            }
                            case "executeQuery" -> {
                                Object[] w = watermarks.get((String) bound.get(1));
                                return w == null ? result() : result(((Instant) w[1]).atOffset(ZoneOffset.UTC));
                            }
                            case "executeUpdate" -> {
                                String key = (String) bound.get(1);
                                Instant value = ((OffsetDateTime) bound.get(3)).toInstant();
                                Object[] cur = watermarks.get(key);
                                if (cur != null && !value.isAfter((Instant) cur[1])) {
                                    return 0;
                                }
                                watermarks.put(key, new Object[]{bound.get(2), value, bound.get(4)});
                                return 1;
                            }
                            default -> {
                                return defaultFor(m);
                            }
                        }
                    });
        }

        private static ResultSet result(Object... single) {
            boolean[] consumed = {false};
            return (ResultSet) Proxy.newProxyInstance(Db.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "next" -> {
                            boolean has = single.length > 0 && !consumed[0];
                            consumed[0] = true;
                            yield has;
                        }
                        case "getObject" -> single[0];
                        default -> defaultFor(m);
                    });
        }

        private static Object defaultFor(Method m) {
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return Boolean.FALSE;
            if (r == int.class) return 0;
            return null;
        }
    }

    /** A writer that records rows; the parent writer also feeds the parent change times. */
    private static final class Writer implements EntityWriter {
        final String resource;
        final String table;
        final Db parentDb; // non-null only for the parent
        final List<String> written = new ArrayList<>();
        String failId;

        Writer(String resource, String table, Db parentDb) {
            this.resource = resource;
            this.table = table;
            this.parentDb = parentDb;
        }

        @Override public String entity() { return resource; }
        @Override public String resource() { return resource; }
        @Override public String targetTable() { return "s." + table; }
        @Override public void ensureSchemaAndTable(Connection conn) { }
        @Override public String ref(JsonNode row) { return row.path("id").asText(); }

        @Override
        public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String runId) throws SQLException {
            String id = row.path("id").asText();
            if (id.equals(failId)) throw new SQLException("simulated row failure");
            boolean existed = written.contains(id);
            if (!existed) written.add(id);
            if (parentDb != null) parentDb.parentChanged.put(id, Instant.parse(row.path("t").asText()));
            return existed ? EntityRepository.Outcome.UPDATED : EntityRepository.Outcome.INSERTED;
        }
    }

    /** Answers each resource with its body (or a failure), recording the bound sent. */
    private static final class Agent implements IncrementalImportService.Fetcher {
        final Map<String, String> bodies = new HashMap<>();
        final Map<String, RuntimeException> failures = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public String fetchPage(String resource, int start, int limit, String modifiedAfterIso) {
            calls.add(resource + "?" + modifiedAfterIso);
            if (failures.containsKey(resource)) throw failures.get(resource);
            return start == 0 ? bodies.getOrDefault(resource, "[]") : "[]"; // test bodies fit in one short page
        }
    }

    private static String rows(Object... idAndTime) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < idAndTime.length; i += 2) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":\"").append(idAndTime[i]).append("\",\"t\":\"").append(idAndTime[i + 1]).append("\"}");
        }
        return sb.append(']').toString();
    }

    private final Db db = new Db();
    private final Writer parent = new Writer("identity-requests", "kf_identity_request", db);
    private final Writer items = new Writer("identity-request-items", "kf_identity_request_item", null);
    private final Writer approvals = new Writer("identity-request-approvals", "kf_identity_request_approval", null);

    private FamilyIncrementalImportService service(Agent agent) {
        return new FamilyIncrementalImportService(agent, parent, List.of(items, approvals), new WatermarkService("s"),
                OVERLAP);
    }

    private Agent agent(String parents, String itemRows, String approvalRows) {
        Agent a = new Agent();
        a.bodies.put("identity-requests", parents);
        a.bodies.put("identity-request-items", itemRows);
        a.bodies.put("identity-request-approvals", approvalRows);
        return a;
    }

    @Test
    void familyKeyIsDistinctFromTheParentOnlyKey() {
        assertEquals(KEY, FamilyIncrementalImportService.familyKey(parent, List.of(items, approvals)));
        assertTrue(!KEY.equals(WatermarkService.watermarkKey("s.kf_identity_request")));
    }

    @Test
    void firstRunIsAFullBaselineForParentAndChildren() throws Exception {
        Agent a = agent(rows("r1", T1, "r2", T2), rows("i1", T1), rows("a1", T1));
        FamilyIncrementalImportService.Outcome o = service(a).run(db.connection(), "run-1");

        assertEquals(List.of("identity-requests?null", "identity-request-items?null",
                "identity-request-approvals?null"), a.calls, "parent first, then children; no bound");
        assertEquals(3, o.results().size());
        assertEquals(List.of("r1", "r2"), parent.written);
        assertEquals(List.of("i1"), items.written);
        assertEquals(List.of("a1"), approvals.written);
        assertTrue(o.advanced());
        assertEquals(T2, db.watermarks.get(KEY)[1]);
        assertEquals("modified", db.watermarks.get(KEY)[0]);
        assertEquals("SELECT max(coalesce(modified_at, created_at)) FROM s.kf_identity_request", db.maxSql.get(0));
    }

    @Test
    void everyDatasetGetsTheSameOverlappedBound() throws Exception {
        service(agent(rows("r1", T2), "[]", "[]")).run(db.connection(), "run-1");
        Agent a = agent(rows("r3", T3), rows("i3", T3), "[]");
        FamilyIncrementalImportService.Outcome o = service(a).run(db.connection(), "run-2");

        String bound = T2.minus(OVERLAP).toString();
        assertEquals(List.of("identity-requests?" + bound, "identity-request-items?" + bound,
                "identity-request-approvals?" + bound), a.calls);
        assertTrue(o.advanced());
        assertEquals(T3, db.watermarks.get(KEY)[1]);
    }

    @Test
    void aFailedChildRowKeepsTheSharedWatermarkWhereItWas() throws Exception {
        service(agent(rows("r1", T1), "[]", "[]")).run(db.connection(), "run-1");
        approvals.failId = "a3";
        FamilyIncrementalImportService.Outcome o = service(agent(rows("r3", T3), rows("i3", T3), rows("a3", T3)))
                .run(db.connection(), "run-2");

        assertEquals(1, o.failed());
        assertFalse(o.advanced(), "the parent loaded fine, but the family watermark must not move");
        assertEquals(T1, db.watermarks.get(KEY)[1], "the next run re-reads r3 and its children");
        assertTrue(parent.written.contains("r3"), "already-written rows stay (idempotent upserts)");
    }

    @Test
    void aFailedChildFetchNeverAdvancesTheWatermark() throws Exception {
        Agent a = agent(rows("r1", T1), rows("i1", T1), rows("a1", T1));
        a.failures.put("identity-request-approvals", new KfAgentClient.KfAgentHttpException("HTTP 500"));
        assertThrows(KfAgentClient.KfAgentHttpException.class, () -> service(a).run(db.connection(), "run-1"));
        assertTrue(approvals.written.isEmpty(), "nothing of the failed dataset is written");
        assertTrue(parent.written.contains("r1"), "pages already read stay written (idempotent upserts)");
        assertNull(db.watermarks.get(KEY), "no watermark: the next run reads the same window again");
    }

    @Test
    void aNonArrayChildResponseNeverAdvancesTheWatermark() {
        Agent a = agent(rows("r1", T1), "{\"error\":\"Unauthorized\"}", "[]");
        assertThrows(KfAgentClient.KfAgentHttpException.class, () -> service(a).run(db.connection(), "run-1"));
        assertTrue(items.written.isEmpty());
        assertNull(db.watermarks.get(KEY));
    }
}

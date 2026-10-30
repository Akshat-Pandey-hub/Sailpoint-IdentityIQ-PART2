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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-1 incremental for append-only event datasets ({@link IncrementalCursor#CREATED}): the bound and the
 * watermark follow IIQ {@code created} ({@code max(created_at)}), the overlap window re-reads boundary events that
 * the append dedup absorbs, failures never advance the watermark, and the existing {@code modified} cursor is the
 * unchanged default.
 */
class EventIncrementalImportTest {

    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-05T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-09T10:00:00Z");
    private static final Duration OVERLAP = Duration.ofMinutes(5);

    /** An append-only destination (id -> created_at) plus kf_extraction_watermark, behind JDBC proxies. */
    private static final class Db {
        final Map<String, Instant> created = new LinkedHashMap<>();
        final Map<String, Object[]> watermarks = new LinkedHashMap<>(); // entity -> {field, instant, runId}
        final List<String> maxQueries = new ArrayList<>();

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "createStatement" -> statement();
                        case "prepareStatement" -> prepared((String) a[0]);
                        default -> defaultFor(m);
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                    (p, m, a) -> {
                        if (m.getName().equals("executeQuery")) {
                            String sql = (String) a[0];
                            maxQueries.add(sql);
                            Instant max = created.values().stream().max(Instant::compareTo).orElse(null);
                            return result(max == null ? null : max.atOffset(ZoneOffset.UTC));
                        }
                        return defaultFor(m);
                    });
        }

        private PreparedStatement prepared(String sql) {
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
                                String entity = (String) bound.get(1);
                                Instant value = ((OffsetDateTime) bound.get(3)).toInstant();
                                Object[] cur = watermarks.get(entity);
                                if (cur != null && !value.isAfter((Instant) cur[1])) {
                                    return 0;
                                }
                                watermarks.put(entity, new Object[]{bound.get(2), value, bound.get(4)});
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

    /** Append-only writer: a new event is INSERTED, an already-stored one SKIPPED (the dedup on append). */
    private static final class AppendWriter implements EntityWriter {
        final Db db;
        String failId;

        AppendWriter(Db db) {
            this.db = db;
        }

        @Override public String entity() { return "AuditEvent"; }
        @Override public String resource() { return "audit-events"; }
        @Override public String targetTable() { return "s.kf_audit_event"; }
        @Override public void ensureSchemaAndTable(Connection conn) { }
        @Override public String ref(JsonNode row) { return row.path("id").asText(); }

        @Override
        public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String runId) throws SQLException {
            String id = row.path("id").asText();
            if (id.equals(failId)) throw new SQLException("simulated row failure");
            if (db.created.containsKey(id)) return EntityRepository.Outcome.SKIPPED;
            db.created.put(id, Instant.parse(row.path("created_at").asText()));
            return EntityRepository.Outcome.INSERTED;
        }
    }

    private static final class Agent implements IncrementalImportService.Fetcher {
        final List<String> bounds = new ArrayList<>();
        String body;

        Agent(String body) {
            this.body = body;
        }

        @Override
        public String fetchPage(String resource, int start, int limit, String modifiedAfterIso) {
            assertEquals("audit-events", resource);
            bounds.add(modifiedAfterIso);
            return start == 0 ? body : "[]"; // every test body fits in the first (short) page
        }
    }

    private static String rows(Object... idAndCreated) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < idAndCreated.length; i += 2) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":\"").append(idAndCreated[i]).append("\",\"created_at\":\"")
                    .append(idAndCreated[i + 1]).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static IncrementalImportService service(Agent agent, AppendWriter writer) {
        return new IncrementalImportService(agent, writer, new WatermarkService("s"), OVERLAP,
                IncrementalCursor.CREATED);
    }

    @Test
    void firstRunIsAFullFetchAndTheWatermarkFollowsCreated() throws Exception {
        Db db = new Db();
        Agent agent = new Agent(rows("e1", T1, "e2", T2));
        IncrementalImportService.Outcome o = service(agent, new AppendWriter(db)).run(db.connection(), "run-1");

        assertEquals(Arrays.asList((String) null), agent.bounds, "no watermark -> full fetch (baseline)");
        assertEquals(2, o.result().inserted());
        assertTrue(o.advanced());
        assertEquals(T2, o.newWatermark());
        Object[] w = db.watermarks.get("native:kf_audit_event");
        assertEquals("created", w[0], "the watermark records the IIQ field it follows");
        assertEquals(T2, w[1]);
        assertEquals("SELECT max(created_at) FROM s.kf_audit_event", db.maxQueries.get(0));
    }

    @Test
    void nextRunSendsTheOverlappedBoundAndTheAppendDedupAbsorbsTheOverlap() throws Exception {
        Db db = new Db();
        service(new Agent(rows("e1", T1, "e2", T2)), new AppendWriter(db)).run(db.connection(), "run-1");

        Agent agent = new Agent(rows("e2", T2, "e3", T3)); // e2 re-read by the overlap window
        IncrementalImportService.Outcome o = service(agent, new AppendWriter(db)).run(db.connection(), "run-2");

        assertEquals(List.of(T2.minus(OVERLAP).toString()), agent.bounds);
        assertEquals(1, o.result().inserted());
        assertEquals(1, o.result().skipped(), "the overlapping event is not stored twice");
        assertEquals(3, db.created.size());
        assertEquals(T3, db.watermarks.get("native:kf_audit_event")[1]);
    }

    @Test
    void aRowFailureNeverAdvancesTheWatermark() throws Exception {
        Db db = new Db();
        service(new Agent(rows("e1", T1)), new AppendWriter(db)).run(db.connection(), "run-1");

        AppendWriter writer = new AppendWriter(db);
        writer.failId = "e3";
        IncrementalImportService.Outcome o = service(new Agent(rows("e2", T2, "e3", T3)), writer)
                .run(db.connection(), "run-2");

        assertEquals(1, o.result().failed());
        assertFalse(o.advanced());
        assertEquals(T1, db.watermarks.get("native:kf_audit_event")[1], "the next run re-reads from T1");
    }

    @Test
    void anEmptyIncrementLeavesTheWatermarkWhereItWas() throws Exception {
        Db db = new Db();
        service(new Agent(rows("e1", T1)), new AppendWriter(db)).run(db.connection(), "run-1");
        IncrementalImportService.Outcome o = service(new Agent("[]"), new AppendWriter(db))
                .run(db.connection(), "run-2");
        assertEquals(0, o.result().fetched());
        assertFalse(o.advanced(), "nothing newer -> no movement");
        assertEquals(T1, db.watermarks.get("native:kf_audit_event")[1]);
    }

    @Test
    void cursorsMapToTheirFieldsAndColumns() {
        assertEquals("modified", IncrementalCursor.MODIFIED.sourceField());
        assertEquals("modified_at", IncrementalCursor.MODIFIED.column());
        assertEquals("created", IncrementalCursor.CREATED.sourceField());
        assertEquals("created_at", IncrementalCursor.CREATED.column());
        assertNull(WatermarkService.effectiveBound(T1, ExtractionMode.FULL, OVERLAP), "full mode is never bounded");
    }
}

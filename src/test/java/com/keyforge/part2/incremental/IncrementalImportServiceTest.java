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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IncrementalImportService} against an in-memory destination table and {@code kf_extraction_watermark} (the
 * fake answers the watermark read, {@code max(modified_at)} and the forward-only advance): first run, overlap bound,
 * successful advancement, failures, interrupted runs, no backward movement, and no sweep.
 */
class IncrementalImportServiceTest {

    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-05T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-09T10:00:00Z");
    private static final Duration OVERLAP = Duration.ofMinutes(5);

    /** Destination rows (id -> modified_at, deleted flag) and the watermark table, behind JDBC proxies. */
    private static final class Db {
        final Map<String, Instant> modified = new LinkedHashMap<>();
        final Set<String> deleted = new HashSet<>();
        final Map<String, Object[]> watermarks = new LinkedHashMap<>(); // entity -> {field, instant, runId}

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
                            assertTrue(sql.startsWith("SELECT max(modified_at) FROM s.kf_x"), sql);
                            Instant max = modified.values().stream().filter(v -> v != null)
                                    .max(Instant::compareTo).orElse(null);
                            return result(max == null ? null : max.atOffset(ZoneOffset.UTC));
                        }
                        return defaultFor(m); // CREATE SCHEMA / CREATE TABLE
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
                                assertTrue(sql.contains("kf_extraction_watermark") && sql.contains("WHERE"), sql);
                                String entity = (String) bound.get(1);
                                Instant value = ((OffsetDateTime) bound.get(3)).toInstant();
                                Object[] cur = watermarks.get(entity);
                                if (cur != null && !value.isAfter((Instant) cur[1])) {
                                    return 0; // the forward-only conflict update did not apply
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

    /** The entity's ordinary writer: upsert sets modified_at and clears is_deleted (Part 2's upsert). */
    private static final class Writer implements EntityWriter {
        final Db db;
        final Set<String> failIds = new HashSet<>();
        final List<String> written = new ArrayList<>();

        Writer(Db db) {
            this.db = db;
        }

        @Override public String entity() { return "X"; }
        @Override public String resource() { return "identities"; }
        @Override public String targetTable() { return "s.kf_x"; }
        @Override public void ensureSchemaAndTable(Connection conn) { }
        @Override public String ref(JsonNode row) { return row.path("id").asText(); }

        @Override
        public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String runId) throws SQLException {
            String id = row.path("id").asText();
            if (failIds.contains(id)) throw new SQLException("simulated row failure");
            written.add(id + "@" + runId);
            boolean existed = db.modified.containsKey(id);
            db.modified.put(id, Instant.parse(row.path("modified_at").asText()));
            db.deleted.remove(id);
            return existed ? EntityRepository.Outcome.UPDATED : EntityRepository.Outcome.INSERTED;
        }
    }

    /** Records each fetch's modifiedAfter and answers with the given body. */
    private static final class Agent implements IncrementalImportService.Fetcher {
        final List<String> bounds = new ArrayList<>();
        String body;
        RuntimeException failure;

        Agent(String body) {
            this.body = body;
        }

        @Override
        public KfAgentClient.Snapshot fetch(String resource, String modifiedAfterIso) {
            assertEquals("identities", resource);
            bounds.add(modifiedAfterIso);
            if (failure != null) throw failure;
            return new KfAgentClient.Snapshot(body, null);
        }
    }

    private static String rows(Object... idAndModified) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < idAndModified.length; i += 2) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":\"").append(idAndModified[i]).append("\",\"modified_at\":\"")
                    .append(idAndModified[i + 1]).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static IncrementalImportService service(Agent agent, Writer writer) {
        return new IncrementalImportService(agent, writer, new WatermarkService("s"), OVERLAP);
    }

    @Test
    void aFirstRunFetchesEverythingAndSetsTheBaseline() throws Exception {
        Db db = new Db();
        Agent agent = new Agent(rows("a", T1, "b", T2));
        IncrementalImportService.Outcome o = service(agent, new Writer(db)).run(db.connection(), "run-1");

        assertEquals(java.util.Arrays.asList((String) null), agent.bounds, "no watermark -> no modifiedAfter (full)");
        assertNull(o.stored());
        assertNull(o.bound());
        assertEquals(2, o.result().inserted());
        assertTrue(o.advanced());
        assertEquals(T2, o.newWatermark());
        Object[] w = db.watermarks.get("native:kf_x");
        assertEquals("modified", w[0]);
        assertEquals(T2, w[1]);
        assertEquals("run-1", w[2], "last_run_id = the run that set it");
    }

    @Test
    void aLaterRunSendsTheWatermarkMinusTheOverlapAndAdvances() throws Exception {
        Db db = new Db();
        db.modified.put("a", T1);
        db.modified.put("b", T2);
        db.watermarks.put("native:kf_x", new Object[]{"modified", T2, "run-1"});
        Agent agent = new Agent(rows("b", T2, "c", T3)); // b re-read inside the overlap, c changed

        Writer writer = new Writer(db);
        IncrementalImportService.Outcome o = service(agent, writer).run(db.connection(), "run-2");

        assertEquals(List.of(T2.minus(OVERLAP).toString()), agent.bounds);
        assertEquals(T2.minus(OVERLAP), o.bound());
        assertEquals(1, o.result().inserted());
        assertEquals(1, o.result().updated(), "the overlap re-read is an idempotent update");
        assertEquals(List.of("b@run-2", "c@run-2"), writer.written, "rows carry this run's extraction_run_id");
        assertTrue(o.advanced());
        assertEquals(T3, db.watermarks.get("native:kf_x")[1]);
        assertEquals("run-2", db.watermarks.get("native:kf_x")[2]);
    }

    @Test
    void nothingNewLeavesTheWatermarkWhereItIs() throws Exception {
        Db db = new Db();
        db.modified.put("b", T2);
        db.watermarks.put("native:kf_x", new Object[]{"modified", T2, "run-1"});
        IncrementalImportService.Outcome o = service(new Agent("[]"), new Writer(db)).run(db.connection(), "run-3");
        assertFalse(o.advanced());
        assertEquals(T2, db.watermarks.get("native:kf_x")[1]);
        assertEquals("run-1", db.watermarks.get("native:kf_x")[2]);
    }

    @Test
    void aRowFailureNeverAdvancesSoTheNextRunReReadsTheSameWindow() throws Exception {
        Db db = new Db();
        db.watermarks.put("native:kf_x", new Object[]{"modified", T1, "run-1"});
        Writer writer = new Writer(db);
        writer.failIds.add("c");
        Agent agent = new Agent(rows("b", T2, "c", T3));
        IncrementalImportService.Outcome o = service(agent, writer).run(db.connection(), "run-2");

        assertEquals(1, o.result().failed());
        assertFalse(o.advanced());
        assertEquals(T1, db.watermarks.get("native:kf_x")[1], "watermark unchanged");

        writer.failIds.clear();
        Agent retry = new Agent(rows("b", T2, "c", T3));
        IncrementalImportService.Outcome again = service(retry, writer).run(db.connection(), "run-3");
        assertEquals(List.of(T1.minus(OVERLAP).toString()), retry.bounds, "same bound as the failed run");
        assertTrue(again.advanced());
        assertEquals(T3, db.watermarks.get("native:kf_x")[1]);
    }

    @Test
    void anInterruptedRunNeverAdvances() {
        Db db = new Db();
        db.watermarks.put("native:kf_x", new Object[]{"modified", T1, "run-1"});
        Agent down = new Agent("[]");
        down.failure = new KfAgentClient.KfAgentHttpException("GET /kfagent/identities returned HTTP 500");
        assertThrows(KfAgentClient.KfAgentHttpException.class,
                () -> service(down, new Writer(db)).run(db.connection(), "run-2"));

        Agent malformed = new Agent("<html>proxy error</html>");
        assertThrows(KfAgentClient.KfAgentHttpException.class,
                () -> service(malformed, new Writer(db)).run(db.connection(), "run-3"));

        Agent errorBody = new Agent("{\"error\":\"NativeImportException: plugin error\"}");
        assertThrows(KfAgentClient.KfAgentHttpException.class,
                () -> service(errorBody, new Writer(db)).run(db.connection(), "run-4"));

        assertEquals(T1, db.watermarks.get("native:kf_x")[1]);
        assertEquals("run-1", db.watermarks.get("native:kf_x")[2]);
    }

    @Test
    void aConcurrentRunThatAdvancedFurtherIsNeverMovedBack() throws Exception {
        Db db = new Db();
        db.modified.put("a", T2);
        // another run already advanced the watermark past what this run will compute
        db.watermarks.put("native:kf_x", new Object[]{"modified", T3, "run-fast"});
        IncrementalImportService.Outcome o = service(new Agent(rows("a", T2)), new Writer(db))
                .run(db.connection(), "run-slow");
        assertEquals(T2, o.newWatermark());
        assertFalse(o.advanced(), "the forward-only write refused the older value");
        assertEquals(T3, db.watermarks.get("native:kf_x")[1]);
        assertEquals("run-fast", db.watermarks.get("native:kf_x")[2]);
    }

    @Test
    void anIncrementalRunNeverSweepsAndRevivesOnlyRowsItReturned() throws Exception {
        Db db = new Db();
        db.modified.put("a", T1);
        db.modified.put("b", T1);
        db.deleted.add("b"); // previously marked deleted by a full run
        db.watermarks.put("native:kf_x", new Object[]{"modified", T1, "run-1"});
        IncrementalImportService.Outcome o = service(new Agent(rows("b", T3)), new Writer(db))
                .run(db.connection(), "run-2");
        assertFalse(db.deleted.contains("b"), "a returned row is revived by the ordinary upsert");
        assertTrue(db.modified.containsKey("a"), "a row not returned is untouched — not marked deleted");
        assertEquals(1, o.result().inserted() + o.result().updated());
    }
}

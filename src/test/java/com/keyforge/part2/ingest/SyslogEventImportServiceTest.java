package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.http.KfAgentClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large-volume safety of {@link SyslogEventImportService}: multi-page paging, final-page termination, overlap /
 * duplicate rows, source-count growth and shrink, missing source count, malformed / error bodies, incomplete scans,
 * idempotent re-runs, and streaming (each page is written before the next one is fetched).
 */
class SyslogEventImportServiceTest {

    /** In-memory append-only writer: a "table" of ids (ON CONFLICT DO NOTHING) plus an event log. */
    private static final class MemoryWriter implements EntityWriter {
        final Set<String> table = new HashSet<>();
        final List<String> log;
        String failId;

        MemoryWriter(List<String> log) {
            this.log = log;
        }

        @Override public String entity() { return "SyslogEvent"; }
        @Override public String resource() { return SyslogEventRepository.RESOURCE; }
        @Override public String targetTable() { return "s.kf_syslog_event"; }
        @Override public void ensureSchemaAndTable(Connection conn) { }
        @Override public String ref(JsonNode row) { return row.path("source_id").asText(); }

        @Override
        public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String runId) throws SQLException {
            String id = row.path("source_id").asText();
            log.add("write " + id);
            if (id.equals(failId)) {
                throw new SQLException("simulated failure");
            }
            return table.add(id) ? EntityRepository.Outcome.INSERTED : EntityRepository.Outcome.SKIPPED;
        }
    }

    /** Source of {@code total} events in (created, id) order, paged by offset like the KF Agent. */
    private static final class Agent implements SyslogEventImportService.PageFetcher {
        final int total;
        final List<String> log;
        IntFunction<Integer> countForPage; // page index -> header value (null = header absent)
        IntFunction<String> bodyForPage;   // page index -> body override
        IntUnaryOperator offsetShift = p -> 0; // page index -> window shift (+ = re-read earlier rows, - = skip rows)
        int pages;

        Agent(int total, List<String> log) {
            this.total = total;
            this.log = log;
            this.countForPage = p -> total;
        }

        @Override
        public KfAgentClient.Page fetch(String resource, int start, int limit) {
            int p = pages++;
            log.add("fetch " + start);
            String body = bodyForPage == null ? null : bodyForPage.apply(p);
            if (body == null) {
                int from = Math.max(0, start - offsetShift.applyAsInt(p));
                StringBuilder sb = new StringBuilder("[");
                for (int i = from; i < Math.min(total, from + limit); i++) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append(row(i));
                }
                body = sb.append(']').toString();
            }
            return new KfAgentClient.Page(body, countForPage.apply(p));
        }

        static String row(int i) {
            return "{\"source_id\":\"" + String.format("%032x", i + 1) + "\",\"quick_key\":\"" + i + "\","
                    + "\"event_level\":\"ERROR\",\"server\":\"h\",\"username\":\"system\",\"thread\":\"main\","
                    + "\"classname\":\"C\",\"line_number\":\"1\",\"message\":\"m\",\"stacktrace\":null,"
                    + "\"created_at\":\"2026-01-01T00:" + String.format("%02d:%02d", (i / 60) % 60, i % 60) + "Z\"}";
        }
    }

    private static Connection conn() {
        return (Connection) Proxy.newProxyInstance(SyslogEventImportServiceTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> m.getReturnType() == boolean.class ? Boolean.FALSE : null);
    }

    @Test
    void multiplePagesEndOnTheShortFinalPageAndStreamPageByPage() throws Exception {
        List<String> log = new ArrayList<>();
        Agent agent = new Agent(25, log);
        MemoryWriter writer = new MemoryWriter(log);
        SyslogEventImportService svc = new SyslogEventImportService(agent, writer, 10);

        ImportResult r = svc.run(conn(), "run-1");

        assertEquals(3, r.pages(), "10 + 10 + 5");
        assertEquals(25, r.fetched());
        assertEquals(25, r.inserted());
        assertEquals(25, svc.baselineSourceCount());
        assertEquals(25, svc.inOrderRows());
        assertEquals(0, svc.overlapRows());
        assertEquals(List.of("fetch 0", "fetch 10", "fetch 20"),
                log.stream().filter(s -> s.startsWith("fetch")).toList(), "consecutive offsets");
        assertTrue(log.indexOf("fetch 10") > log.indexOf("write " + String.format("%032x", 10)),
                "page 1 is written before page 2 is fetched (streaming, no full accumulation)");
    }

    @Test
    void anExactMultipleEndsOnTheEmptyPage() throws Exception {
        List<String> log = new ArrayList<>();
        Agent agent = new Agent(20, log);
        SyslogEventImportService svc = new SyslogEventImportService(agent, new MemoryWriter(log), 10);
        ImportResult r = svc.run(conn(), "run-2");
        assertEquals(3, r.pages(), "10 + 10 + empty");
        assertEquals(20, r.inserted());
    }

    @Test
    void aSecondRunSkipsEveryExistingEvent() throws Exception {
        List<String> log = new ArrayList<>();
        MemoryWriter writer = new MemoryWriter(log);
        new SyslogEventImportService(new Agent(25, log), writer, 10).run(conn(), "run-a");
        ImportResult again = new SyslogEventImportService(new Agent(25, log), writer, 10).run(conn(), "run-b");
        assertEquals(0, again.inserted());
        assertEquals(25, again.skipped(), "append-only: existing ids are skipped, never updated");
        assertEquals(0, again.updated());
    }

    @Test
    void overlappingRowsAreAppendedIdempotentlyButNotCreditedTowardCompleteness() throws Exception {
        List<String> log = new ArrayList<>();
        Agent agent = new Agent(25, log);
        agent.offsetShift = p -> p == 0 ? 0 : 2; // window moved: last 2 rows of page 1 come back on page 2
        SyslogEventImportService svc = new SyslogEventImportService(agent, new MemoryWriter(log), 10);

        ImportResult r = svc.run(conn(), "run-3");

        assertEquals(2, svc.overlapRows());
        assertEquals(2, r.skipped(), "the repeated rows are skipped by the idempotent insert");
        assertEquals(25, svc.inOrderRows(), "every distinct event still read once in order");
    }

    @Test
    void duplicatesCannotMaskAGap() {
        List<String> log = new ArrayList<>();
        Agent agent = new Agent(25, log); // source count stays 25 throughout
        // page 2 skips rows 10..12 (a gap), page 3 re-reads rows 17..22: 10 + 10 + 8 = 28 rows fetched >= 25,
        // so a raw row count would wrongly pass; only 22 distinct rows were read in order.
        agent.offsetShift = p -> p == 1 ? -3 : (p == 2 ? 3 : 0);
        SyslogEventImportService svc = new SyslogEventImportService(agent, new MemoryWriter(log), 10);

        SyslogEventImportService.IncompleteScanException e =
                assertThrows(SyslogEventImportService.IncompleteScanException.class, () -> svc.run(conn(), "run-4"));
        assertEquals(28, e.partial().fetched(), "a naive fetched >= sourceCount check would have passed");
        assertEquals(22, svc.inOrderRows());
        assertEquals(6, svc.overlapRows());
        assertTrue(e.getMessage().contains("baseline source count 25") && e.getMessage().contains("read 22"));
    }

    @Test
    void sourceCountGrowthDuringTheScanIsBenign() throws Exception {
        List<String> log = new ArrayList<>();
        Agent agent = new Agent(25, log);
        agent.countForPage = p -> p == 0 ? 23 : 25; // two events appended while scanning
        SyslogEventImportService svc = new SyslogEventImportService(agent, new MemoryWriter(log), 10);
        ImportResult r = svc.run(conn(), "run-5");
        assertEquals(25, r.inserted());
        assertEquals(23, svc.baselineSourceCount());
        assertEquals(25, svc.highWaterSourceCount());
    }

    @Test
    void sourceCountShrinkingDuringTheScanFails() {
        List<String> log = new ArrayList<>();
        Agent agent = new Agent(25, log);
        agent.countForPage = p -> p == 0 ? 25 : 24; // an event was purged mid-scan
        SyslogEventImportService svc = new SyslogEventImportService(agent, new MemoryWriter(log), 10);
        SyslogEventImportService.IncompleteScanException e =
                assertThrows(SyslogEventImportService.IncompleteScanException.class, () -> svc.run(conn(), "run-6"));
        assertTrue(e.getMessage().contains("shrank") && e.getMessage().contains("25 -> 24"));
        assertEquals(10, e.partial().inserted(), "only page 1 was written before the shrink was seen");
    }

    @Test
    void aMissingSourceCountHeaderRefusesTheRunBeforeWritingAnything() {
        List<String> log = new ArrayList<>();
        Agent agent = new Agent(25, log);
        agent.countForPage = p -> null; // an older KF Agent
        MemoryWriter writer = new MemoryWriter(log);
        SyslogEventImportService svc = new SyslogEventImportService(agent, writer, 10);
        SyslogEventImportService.IncompleteScanException e =
                assertThrows(SyslogEventImportService.IncompleteScanException.class, () -> svc.run(conn(), "run-7"));
        assertTrue(e.getMessage().contains(KfAgentClient.SOURCE_COUNT_HEADER));
        assertTrue(writer.table.isEmpty(), "nothing written without a completeness baseline");
    }

    @Test
    void aScanThatEndsEarlyIsIncomplete() {
        List<String> log = new ArrayList<>();
        Agent agent = new Agent(15, log);
        agent.countForPage = p -> 25; // source claims 25, agent returns a short page after 15
        SyslogEventImportService svc = new SyslogEventImportService(agent, new MemoryWriter(log), 10);
        SyslogEventImportService.IncompleteScanException e =
                assertThrows(SyslogEventImportService.IncompleteScanException.class, () -> svc.run(conn(), "run-8"));
        assertTrue(e.getMessage().contains("baseline source count 25") && e.getMessage().contains("read 15"));
        assertEquals(15, e.partial().inserted());
    }

    @Test
    void malformedAndErrorBodiesFail() {
        List<String> log = new ArrayList<>();
        Agent html = new Agent(25, log);
        html.bodyForPage = p -> p == 1 ? "<html>gateway timeout</html>" : null;
        assertThrows(KfAgentClient.KfAgentHttpException.class,
                () -> new SyslogEventImportService(html, new MemoryWriter(log), 10).run(conn(), "run-9"));

        Agent error = new Agent(25, log);
        error.bodyForPage = p -> "{\"error\":\"NativeImportException: plugin returned an error\"}";
        KfAgentClient.KfAgentHttpException e = assertThrows(KfAgentClient.KfAgentHttpException.class,
                () -> new SyslogEventImportService(error, new MemoryWriter(log), 10).run(conn(), "run-10"));
        assertTrue(e.getMessage().contains("plugin returned an error"));
    }

    @Test
    void aRowFailureIsReportedButTheScanStillCompletes() throws Exception {
        List<String> log = new ArrayList<>();
        MemoryWriter writer = new MemoryWriter(log);
        writer.failId = String.format("%032x", 7);
        SyslogEventImportService svc = new SyslogEventImportService(new Agent(25, log), writer, 10);
        ImportResult r = svc.run(conn(), "run-11");
        assertEquals(1, r.failed());
        assertEquals(24, r.inserted());
        assertEquals(25, svc.inOrderRows(), "a failed write is still a row seen by the scan, as in Part 1");
    }

    @Test
    void orderingKeyComparesInstantThenId() {
        java.time.Instant t1 = java.time.Instant.parse("2026-01-01T00:00:00.250Z");
        java.time.Instant t2 = java.time.Instant.parse("2026-01-01T00:00:00.25Z");
        assertTrue(SyslogEventImportService.after(t1, "b", null, null), "first row");
        assertTrue(SyslogEventImportService.after(t1, "b", t1, "a"), "same instant, larger id");
        assertTrue(!SyslogEventImportService.after(t2, "a", t1, "a"),
                "the same instant written with different precision is a duplicate, not a new row");
        assertTrue(!SyslogEventImportService.after(t1, "a", t1, "b"), "going backwards is an overlap");
    }

    @Test
    void sourceCountHeaderParsing() {
        assertEquals(Integer.valueOf(217925), KfAgentClient.sourceCount("217925"));
        assertEquals(Integer.valueOf(5), KfAgentClient.sourceCount(" 5 "));
        assertEquals(null, KfAgentClient.sourceCount(null));
        assertEquals(null, KfAgentClient.sourceCount(" "));
        assertEquals(null, KfAgentClient.sourceCount("abc"));
    }
}

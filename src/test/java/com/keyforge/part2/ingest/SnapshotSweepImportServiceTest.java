package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keyforge.part2.http.KfAgentClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Importer → sweeper integration ({@link SnapshotSweepImportService}): the writer and the sweep act on ONE in-memory
 * table (the sweep's mark / revive statements executed with commit / rollback), so each test checks the resulting rows.
 */
class SnapshotSweepImportServiceTest {

    private static final String A = "00000000-0000-0000-0000-00000000000a";
    private static final String B = "00000000-0000-0000-0000-00000000000b";
    private static final String C = "00000000-0000-0000-0000-00000000000c";
    private static final String D = "00000000-0000-0000-0000-00000000000d";
    private static final String E = "00000000-0000-0000-0000-00000000000e";
    private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private record Row(boolean deleted, Instant deletedAt) { }

    /** The table both the writer and the sweeper's SQL operate on. */
    private static final class Db {
        Map<String, Row> committed = new LinkedHashMap<>();
        Map<String, Row> working;
        boolean autoCommit = true;
        boolean failOnRevive;
        boolean connectionLost;
        String table = "kf_t";
        String pk = "tid";
        final List<String> sweepSql = new ArrayList<>();

        Db live(String... ids) {
            for (String id : ids) committed.put(id, new Row(false, null));
            return this;
        }

        Db deleted(String id, Instant at) {
            committed.put(id, new Row(true, at));
            return this;
        }

        Map<String, Row> view() {
            return autoCommit ? committed : working;
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getAutoCommit" -> autoCommit;
                        case "setAutoCommit" -> {
                            boolean on = (Boolean) a[0];
                            if (!on && autoCommit) working = new LinkedHashMap<>(committed);
                            autoCommit = on;
                            yield null;
                        }
                        case "commit" -> {
                            committed = new LinkedHashMap<>(working);
                            yield null;
                        }
                        case "rollback" -> {
                            working = new LinkedHashMap<>(committed);
                            yield null;
                        }
                        case "createArrayOf" -> connectionLost ? fail("connection lost") : (Array) Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{Array.class},
                                (p2, m2, a2) -> m2.getName().equals("getArray") ? a[1] : defaultFor(m2));
                        case "prepareStatement" -> statement((String) a[0]);
                        default -> defaultFor(m);
                    });
        }

        private PreparedStatement statement(String sql) {
            Object[] bound = new Object[1];
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        if (m.getName().equals("setArray")) {
                            bound[0] = a[1];
                            return null;
                        }
                        if (m.getName().equals("executeUpdate")) {
                            sweepSql.add(sql);
                            Set<String> keys = new HashSet<>();
                            for (Object o : (Object[]) ((Array) bound[0]).getArray()) keys.add((String) o);
                            return apply(sql, keys);
                        }
                        return defaultFor(m);
                    });
        }

        private int apply(String sql, Set<String> keys) throws SQLException {
            int n = 0;
            if (sql.equals(SoftDeleteSweeper.markSql("s." + table, pk))) {
                for (Map.Entry<String, Row> e : view().entrySet()) {
                    if (!keys.contains(e.getKey()) && !e.getValue().deleted()) {
                        e.setValue(new Row(true, NOW));
                        n++;
                    }
                }
                return n;
            }
            if (sql.equals(SoftDeleteSweeper.reviveSql("s." + table, pk))) {
                if (failOnRevive) throw new SQLException("simulated revive failure");
                for (Map.Entry<String, Row> e : view().entrySet()) {
                    if (keys.contains(e.getKey()) && e.getValue().deleted()) {
                        e.setValue(new Row(false, null));
                        n++;
                    }
                }
                return n;
            }
            throw new SQLException("unexpected SQL " + sql);
        }

        private static Object fail(String message) throws SQLException {
            throw new SQLException(message);
        }

        private static Object defaultFor(Method m) {
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return Boolean.FALSE;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            return null;
        }
    }

    /** Writer over the Db; {@code resetOnUpsert} = Part 2's upsert behaviour (is_deleted=false on every write). */
    private static final class Writer implements EntityWriter {
        final Db db;
        final boolean resetOnUpsert;
        final Set<String> failIds = new HashSet<>();
        boolean failEnsure;
        int upserts;

        Writer(Db db, boolean resetOnUpsert) {
            this.db = db;
            this.resetOnUpsert = resetOnUpsert;
        }

        @Override public String entity() { return "T"; }
        @Override public String resource() { return "things"; }
        @Override public String targetTable() { return "s.kf_t"; }
        @Override public String ref(JsonNode row) { return row.path("id").asText(); }
        @Override public String key(JsonNode row) { return row.path("id").isNull() ? null : row.path("id").asText(null); }

        @Override
        public void ensureSchemaAndTable(Connection conn) throws SQLException {
            if (failEnsure) throw new SQLException("relation does not exist");
        }

        @Override
        public EntityRepository.Outcome upsert(Connection conn, JsonNode row, String runId) throws SQLException {
            String id = row.path("id").asText();
            upserts++;
            if (failIds.contains(id)) throw new SQLException("simulated row failure");
            Row prior = db.committed.get(id);
            db.committed.put(id, resetOnUpsert || prior == null ? new Row(false, null) : prior);
            return prior == null ? EntityRepository.Outcome.INSERTED : EntityRepository.Outcome.UPDATED;
        }
    }

    private static String body(String... ids) {
        StringBuilder sb = new StringBuilder("[");
        for (String id : ids) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":\"").append(id).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static SweepPolicies.SweepConfig config(SweepPolicy policy, SweepPolicies.Completeness c) {
        return new SweepPolicies.SweepConfig("kf_t", "tid", policy, c);
    }

    private static final SweepPolicies.SweepConfig ROWS =
            config(SweepPolicy.SWEEP_PROTECT_FAILED_KEYS, SweepPolicies.Completeness.ROW_COUNT);

    private static SnapshotSweepImportService.Outcome run(Db db, Writer writer, SweepPolicies.SweepConfig config,
                                                          String body, Integer verified, boolean incremental)
            throws SQLException {
        SnapshotSweepImportService.SnapshotFetcher fetcher = resource -> new KfAgentClient.Snapshot(body, verified);
        return new SnapshotSweepImportService(fetcher, writer, config, new SoftDeleteSweeper("s"), incremental)
                .run(db.connection(), "run-1");
    }

    @Test
    void aConfirmedCompleteSnapshotMarksMissingRowsAndPreservesExistingDeletedAt() throws Exception {
        Db db = new Db().live(A, B, C).deleted(D, EARLIER);
        SnapshotSweepImportService.Outcome o = run(db, new Writer(db, true), ROWS, body(A, B), 2, false);

        assertTrue(o.sweep().swept(), o.sweep().note());
        assertEquals(1, o.sweep().marked(), "only C (D was already deleted)");
        assertEquals(new Row(true, NOW), db.committed.get(C));
        assertEquals(new Row(true, EARLIER), db.committed.get(D), "existing deleted_at preserved");
        assertEquals(new Row(false, null), db.committed.get(A));
        assertEquals(2, o.result().updated());
        assertTrue(o.sweep().note().contains("verified against IIQ count 2 = 2 rows"));
    }

    @Test
    void aReappearingRowIsRevived() throws Exception {
        Db db = new Db().live(A).deleted(E, EARLIER);
        // writer that leaves is_deleted alone (Part 1 sweeper tables): the sweep's revive does the work
        SnapshotSweepImportService.Outcome o = run(db, new Writer(db, false), ROWS, body(A, E), 2, false);
        assertEquals(1, o.sweep().revived());
        assertEquals(new Row(false, null), db.committed.get(E));

        Db db2 = new Db().live(A).deleted(E, EARLIER);
        // Part 2's own upsert already resets is_deleted, so the sweep finds nothing left to revive
        SnapshotSweepImportService.Outcome o2 = run(db2, new Writer(db2, true), ROWS, body(A, E), 2, false);
        assertEquals(0, o2.sweep().revived());
        assertEquals(new Row(false, null), db2.committed.get(E));
    }

    @Test
    void failedRowsAreProtected() throws Exception {
        Db db = new Db().live(A, B, C);
        Writer writer = new Writer(db, true);
        writer.failIds.add(B);
        SnapshotSweepImportService.Outcome o = run(db, writer, ROWS, body(A, B), 2, false);
        assertTrue(o.sweep().swept());
        assertEquals(1, o.result().failed());
        assertFalse(db.committed.get(B).deleted(), "B failed to write but is still in the source");
        assertTrue(db.committed.get(C).deleted());
    }

    @Test
    void theStrictPolicyAbortsTheSweepOnAnyFailure() throws Exception {
        Db db = new Db().live(A, B, C);
        Writer writer = new Writer(db, true);
        writer.failIds.add(B);
        SweepPolicies.SweepConfig strict = config(SweepPolicy.SWEEP_UNLESS_ANY_FAILURE,
                SweepPolicies.Completeness.PARENT_SCAN);
        SnapshotSweepImportService.Outcome o = run(db, writer, strict, body(A, B), 9, false);
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_ROW_FAILURES, o.sweep().status());
        assertFalse(db.committed.get(C).deleted());
        assertTrue(db.sweepSql.isEmpty());
    }

    @Test
    void anEmptyVerifiedPopulationNeverMarksTheTableDeleted() throws Exception {
        Db db = new Db().live(A, B);
        SnapshotSweepImportService.Outcome o = run(db, new Writer(db, true), ROWS, "[]", 0, false);
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_EMPTY_KEEP, o.sweep().status());
        assertTrue(db.committed.values().stream().noneMatch(Row::deleted));
    }

    @Test
    void noVerifiedCountMeansNoSweepButRowsAreStillImported() throws Exception {
        Db db = new Db().live(A, B, C);
        Writer writer = new Writer(db, true);
        SnapshotSweepImportService.Outcome o = run(db, writer, ROWS, body(A, B), null, false);
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_COMPLETENESS_FAILED, o.sweep().status());
        assertTrue(o.sweep().note().contains(KfAgentClient.VERIFIED_SOURCE_COUNT_HEADER));
        assertEquals(2, writer.upserts, "import behaviour unchanged: every row still upserted");
        assertFalse(db.committed.get(C).deleted());
        assertTrue(db.sweepSql.isEmpty());
    }

    @Test
    void aRowCountThatDisagreesWithTheVerifiedCountIsInconsistent() throws Exception {
        Db db = new Db().live(A, B, C);
        SnapshotSweepImportService.Outcome o = run(db, new Writer(db, true), ROWS, body(A, B), 3, false);
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_COMPLETENESS_FAILED, o.sweep().status());
        assertTrue(o.sweep().note().contains("2 rows but the verified source count is 3"));
        assertFalse(db.committed.get(C).deleted());
    }

    @Test
    void aParentScanTableNeedsTheVerifiedCountButNotARowCountMatch() throws Exception {
        Db db = new Db().live(A, B, C);
        SweepPolicies.SweepConfig parent = config(SweepPolicy.SWEEP_PROTECT_FAILED_KEYS,
                SweepPolicies.Completeness.PARENT_SCAN);
        SnapshotSweepImportService.Outcome o = run(db, new Writer(db, true), parent, body(A, B), 68, false);
        assertTrue(o.sweep().swept(), "68 parents verified; 2 edges is fine");
        assertTrue(db.committed.get(C).deleted());

        Db db2 = new Db().live(A, B, C);
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_COMPLETENESS_FAILED,
                run(db2, new Writer(db2, true), parent, body(A, B), null, false).sweep().status());
    }

    @Test
    void httpErrorsMalformedBodiesAndExceptionsNeverReachTheSweep() {
        Db db = new Db().live(A, B, C);
        SnapshotSweepImportService.SnapshotFetcher down = r -> {
            throw new KfAgentClient.KfAgentHttpException("GET /kfagent/things returned HTTP 500: Incomplete scan");
        };
        assertThrows(KfAgentClient.KfAgentHttpException.class, () -> new SnapshotSweepImportService(down,
                new Writer(db, true), ROWS, new SoftDeleteSweeper("s"), false).run(db.connection(), "r"));

        assertThrows(KfAgentClient.KfAgentHttpException.class,
                () -> run(db, new Writer(db, true), ROWS, "<html>proxy error</html>", 2, false));
        assertThrows(KfAgentClient.KfAgentHttpException.class,
                () -> run(db, new Writer(db, true), ROWS, "{\"error\":\"NativeImportException: Incomplete\"}", 2, false));

        Writer broken = new Writer(db, true);
        broken.failEnsure = true;
        assertThrows(SQLException.class, () -> run(db, broken, ROWS, body(A), 1, false));

        assertTrue(db.sweepSql.isEmpty(), "no mark / revive after any failure");
        assertTrue(db.committed.values().stream().noneMatch(Row::deleted));
    }

    @Test
    void anIncrementalRunNeverSweeps() throws Exception {
        Db db = new Db().live(A, B, C);
        SnapshotSweepImportService.Outcome o = run(db, new Writer(db, true), ROWS, body(A, B), 2, true);
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_INCREMENTAL, o.sweep().status());
        assertFalse(db.committed.get(C).deleted());
    }

    @Test
    void aRowWithoutAKeyBlocksTheSweep() throws Exception {
        Db db = new Db().live(A, B, C);
        SnapshotSweepImportService.Outcome o = run(db, new Writer(db, true), ROWS,
                "[{\"id\":\"" + A + "\"},{\"id\":null}]", 2, false);
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_COMPLETENESS_FAILED, o.sweep().status());
        assertFalse(db.committed.get(C).deleted());
    }

    @Test
    void aFailedSweepTransactionLeavesNoPartialMarkOrRevive() {
        Db db = new Db().live(A, B, C).deleted(E, EARLIER);
        db.failOnRevive = true;
        assertThrows(SQLException.class, () -> run(db, new Writer(db, false), ROWS, body(A, B, E), 3, false));
        assertFalse(db.committed.get(C).deleted(), "the mark was rolled back with the failed revive");
        assertEquals(new Row(true, EARLIER), db.committed.get(E));
        assertTrue(db.autoCommit);
    }

    @Test
    void everyEnabledTableSweepsOnlyWithProofAndUnderItsOwnFailurePolicy() throws Exception {
        for (SweepPolicies.SweepConfig c : SweepPolicies.CONFIGS.values()) {
            boolean rowCount = c.completeness() == SweepPolicies.Completeness.ROW_COUNT;
            Integer proof = rowCount ? 2 : 50; // ROW_COUNT: = rows; PARENT_SCAN: the parent count

            Db ok = forTable(c).live(A, B, C);
            SnapshotSweepImportService.Outcome swept = run(ok, new Writer(ok, true), c, body(A, B), proof, false);
            assertTrue(swept.sweep().swept(), c.table() + ": " + swept.sweep().note());
            assertTrue(ok.committed.get(C).deleted(), c.table());

            Db noHeader = forTable(c).live(A, B, C);
            assertEquals(SoftDeleteSweeper.Status.SKIPPED_COMPLETENESS_FAILED,
                    run(noHeader, new Writer(noHeader, true), c, body(A, B), null, false).sweep().status(), c.table());
            assertFalse(noHeader.committed.get(C).deleted());

            if (rowCount) {
                Db mismatch = forTable(c).live(A, B, C);
                assertEquals(SoftDeleteSweeper.Status.SKIPPED_COMPLETENESS_FAILED, run(mismatch,
                        new Writer(mismatch, true), c, body(A, B), 3, false).sweep().status(), c.table());
                assertFalse(mismatch.committed.get(C).deleted());
            }

            Db failing = forTable(c).live(A, B, C);
            Writer w = new Writer(failing, true);
            w.failIds.add(B);
            SoftDeleteSweeper.Result r = run(failing, w, c, body(A, B), proof, false).sweep();
            assertFalse(failing.committed.get(B).deleted(), c.table() + ": a failed row is never marked");
            if (c.policy() == SweepPolicy.SWEEP_UNLESS_ANY_FAILURE) {
                assertEquals(SoftDeleteSweeper.Status.SKIPPED_ROW_FAILURES, r.status(), c.table());
                assertFalse(failing.committed.get(C).deleted());
            } else {
                assertTrue(r.swept(), c.table());
            }
        }
    }

    private static Db forTable(SweepPolicies.SweepConfig c) {
        Db db = new Db();
        db.table = c.table();
        db.pk = c.pkColumn();
        return db;
    }

    @Test
    void aDatabaseLostDuringTheImportCannotMarkAnything() {
        Db db = new Db().live(A, B, C);
        Writer writer = new Writer(db, true);
        writer.failIds.addAll(List.of(A, B)); // every write fails ...
        db.connectionLost = true;             // ... and the sweep cannot even start its statement
        assertThrows(SQLException.class, () -> run(db, writer, ROWS, body(A, B), 2, false));
        assertTrue(db.committed.values().stream().noneMatch(Row::deleted));
        assertTrue(db.autoCommit, "auto-commit restored after the failed sweep");
    }

    // ---- confirmation scan -------------------------------------------------------------------------------------

    /** A fetcher returning {@code scans[i]} on call i (a Supplier so a scan can also throw); counts the calls. */
    private static final class Scans implements SnapshotSweepImportService.SnapshotFetcher {
        final List<Supplier<KfAgentClient.Snapshot>> scans;
        int calls;

        @SafeVarargs
        Scans(Supplier<KfAgentClient.Snapshot>... scans) {
            this.scans = List.of(scans);
        }

        @Override
        public KfAgentClient.Snapshot fetch(String resource) {
            return scans.get(calls++).get();
        }
    }

    private static Supplier<KfAgentClient.Snapshot> scan(String body, Integer verified) {
        return () -> new KfAgentClient.Snapshot(body, verified);
    }

    private static SnapshotSweepImportService.Outcome runScans(Db db, Writer writer, SweepPolicies.SweepConfig config,
                                                               Scans scans) throws SQLException {
        return new SnapshotSweepImportService(scans, writer, config, new SoftDeleteSweeper("s"), false)
                .run(db.connection(), "run-1");
    }

    @Test
    void aLiveRowSkippedByScanOneButPresentInScanTwoIsProtected() throws Exception {
        Db db = new Db().live(A, B, C);
        // scan 1 skipped B (offset shift after a mid-scan removal) and its own count agrees, so it alone looks complete
        Scans scans = new Scans(scan(body(A), 1), scan(body(A, B), 2));
        SnapshotSweepImportService.Outcome o = runScans(db, new Writer(db, true), ROWS, scans);

        assertTrue(o.sweep().swept(), o.sweep().note());
        assertEquals(2, scans.calls, "exactly two scans for an eligible sweep");
        assertFalse(db.committed.get(B).deleted(), "present in the confirmation scan -> kept");
        assertTrue(db.committed.get(C).deleted(), "absent from both scans -> marked");
        assertEquals(1, o.sweep().marked());
        assertTrue(o.sweep().note().contains("confirmed by a second verified scan"));
    }

    @Test
    void aRowAbsentFromBothScansIsMarkedAndARowPresentOnlyInScanOneIsRetained() throws Exception {
        Db db = new Db().live(A, B, C);
        Scans scans = new Scans(scan(body(A, B), 2), scan(body(A), 1)); // B removed between the scans
        SnapshotSweepImportService.Outcome o = runScans(db, new Writer(db, true), ROWS, scans);

        assertTrue(o.sweep().swept());
        assertTrue(db.committed.get(C).deleted(), "absent from both");
        assertFalse(db.committed.get(B).deleted(), "seen in scan 1 -> retained this run (marked by a later run)");
    }

    @Test
    void onlyScanOneRowsAreWritten() throws Exception {
        Db db = new Db().live(A, B);
        Writer writer = new Writer(db, true);
        Scans scans = new Scans(scan(body(A, B), 2), scan(body(A, B, D), 3)); // D appeared after scan 1
        runScans(db, writer, ROWS, scans);
        assertEquals(2, writer.upserts, "the confirmation scan never writes");
        assertFalse(db.committed.containsKey(D), "D is not imported by this run");
    }

    @Test
    void anUnusableConfirmationScanPreventsTheSweep() throws Exception {
        List<Supplier<KfAgentClient.Snapshot>> bad = List.of(
                scan(body(A, B), null),                                  // no verified-count header
                scan(body(A, B), 3),                                     // row count != verified count (ROW_COUNT)
                scan("[{\"id\":\"" + A + "\"},{\"id\":null}]", 2));       // a row without a key
        for (Supplier<KfAgentClient.Snapshot> second : bad) {
            Db db = new Db().live(A, B, C);
            Scans scans = new Scans(scan(body(A, B), 2), second);
            SnapshotSweepImportService.Outcome o = runScans(db, new Writer(db, true), ROWS, scans);
            assertEquals(SoftDeleteSweeper.Status.SKIPPED_COMPLETENESS_FAILED, o.sweep().status(), o.sweep().note());
            assertTrue(o.sweep().note().startsWith("confirmation scan"), o.sweep().note());
            assertFalse(db.committed.get(C).deleted());
            assertTrue(db.sweepSql.isEmpty());
            assertEquals(2, scans.calls);
        }

        Db db = new Db().live(A, B, C);
        SnapshotSweepImportService.Outcome o = runScans(db, new Writer(db, true), ROWS,
                new Scans(scan(body(A, B), 2), scan(body(A, "not-a-uuid"), 2)));
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_INVALID_KEY, o.sweep().status(), "an invalid key in scan 2");
        assertFalse(db.committed.get(C).deleted());
        assertTrue(db.sweepSql.isEmpty());
    }

    @Test
    void aConfirmationScanThatThrowsOrIsMalformedPreventsTheSweep() {
        Db db = new Db().live(A, B, C);
        Writer writer = new Writer(db, true);
        Scans httpError = new Scans(scan(body(A, B), 2), () -> {
            throw new KfAgentClient.KfAgentHttpException("GET /kfagent/things returned HTTP 500: Incomplete scan");
        });
        assertThrows(KfAgentClient.KfAgentHttpException.class, () -> runScans(db, writer, ROWS, httpError));
        assertEquals(2, writer.upserts, "scan-1 rows were written before the confirmation failed");

        Scans malformed = new Scans(scan(body(A, B), 2), scan("<html>proxy error</html>", 2));
        assertThrows(KfAgentClient.KfAgentHttpException.class,
                () -> runScans(db, new Writer(db, true), ROWS, malformed));
        Scans errorBody = new Scans(scan(body(A, B), 2), scan("{\"error\":\"NativeImportException\"}", 2));
        assertThrows(KfAgentClient.KfAgentHttpException.class,
                () -> runScans(db, new Writer(db, true), ROWS, errorBody));

        assertTrue(db.sweepSql.isEmpty(), "no mark / revive after a failed confirmation scan");
        assertTrue(db.committed.values().stream().noneMatch(Row::deleted));
    }

    @Test
    void anIneligibleSweepNeverFetchesTheConfirmationScan() throws Exception {
        Db noHeader = new Db().live(A, B, C);
        Scans one = new Scans(scan(body(A, B), null));
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_COMPLETENESS_FAILED,
                runScans(noHeader, new Writer(noHeader, true), ROWS, one).sweep().status());
        assertEquals(1, one.calls);

        Db empty = new Db().live(A);
        Scans emptyScan = new Scans(scan("[]", 0));
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_EMPTY_KEEP,
                runScans(empty, new Writer(empty, true), ROWS, emptyScan).sweep().status());
        assertEquals(1, emptyScan.calls, "empty keep-set guard holds even with a verified count of zero");
        assertFalse(empty.committed.get(A).deleted());

        Db strict = new Db().live(A, B, C);
        Writer failing = new Writer(strict, true);
        failing.failIds.add(B);
        Scans strictScans = new Scans(scan(body(A, B), 9));
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_ROW_FAILURES, runScans(strict, failing,
                config(SweepPolicy.SWEEP_UNLESS_ANY_FAILURE, SweepPolicies.Completeness.PARENT_SCAN), strictScans)
                .sweep().status());
        assertEquals(1, strictScans.calls);

        Db inc = new Db().live(A, B, C);
        Scans incScans = new Scans(scan(body(A, B), 2));
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_INCREMENTAL, new SnapshotSweepImportService(incScans,
                new Writer(inc, true), ROWS, new SoftDeleteSweeper("s"), true).run(inc.connection(), "r")
                .sweep().status());
        assertEquals(1, incScans.calls);
    }

    @Test
    void aFailedRowFromScanOneStaysProtectedWhenScanTwoOmitsIt() throws Exception {
        Db db = new Db().live(A, B, C);
        Writer writer = new Writer(db, true);
        writer.failIds.add(B);
        Scans scans = new Scans(scan(body(A, B), 2), scan(body(A), 1));
        SnapshotSweepImportService.Outcome o = runScans(db, writer, ROWS, scans);
        assertTrue(o.sweep().swept());
        assertFalse(db.committed.get(B).deleted(), "failed-key protection survives the confirmation scan");
        assertTrue(db.committed.get(C).deleted());
    }

    @Test
    void aFailedSweepTransactionAfterBothScansLeavesNoPartialMarkOrRevive() {
        Db db = new Db().live(A, B, C).deleted(E, EARLIER);
        db.failOnRevive = true;
        Scans scans = new Scans(scan(body(A, B, E), 3), scan(body(A, B, E), 3));
        assertThrows(SQLException.class, () -> runScans(db, new Writer(db, false), ROWS, scans));
        assertEquals(2, scans.calls);
        assertFalse(db.committed.get(C).deleted(), "the mark was rolled back with the failed revive");
        assertEquals(new Row(true, EARLIER), db.committed.get(E));
        assertTrue(db.autoCommit);
    }

    @Test
    void neverSweepTablesHaveNoConfigAndCannotBeEnabled() {
        for (String table : SweepPolicies.NEVER_SWEEP_TABLES) {
            assertTrue(SweepPolicies.configFor(table).isEmpty(), table);
            assertEquals(SweepPolicy.NEVER, SweepPolicies.policyFor("iiq_native_part2." + table));
            assertThrows(IllegalArgumentException.class, () -> new SweepPolicies.SweepConfig(table, "id",
                    SweepPolicy.SWEEP_PROTECT_FAILED_KEYS, SweepPolicies.Completeness.ROW_COUNT).target());
        }
        assertNull(SweepPolicies.configFor(null).orElse(null));
    }
}

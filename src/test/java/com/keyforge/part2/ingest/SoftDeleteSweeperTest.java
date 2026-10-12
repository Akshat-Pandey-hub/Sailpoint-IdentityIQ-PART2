package com.keyforge.part2.ingest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
 * {@link SoftDeleteSweeper} against an in-memory table that executes the mark / revive statements with transaction
 * semantics: mark, revive, preserved deleted_at, failed-key protection, both failure policies, every skip guard
 * (incremental, exception, incomplete pagination, failed / missing completeness proof, empty keep-set, invalid key),
 * NEVER defaults, forbidden tables, rollback, and Part 1 SQL parity.
 */
class SoftDeleteSweeperTest {

    private static final String A = "00000000-0000-0000-0000-00000000000a";
    private static final String B = "00000000-0000-0000-0000-00000000000b";
    private static final String C = "00000000-0000-0000-0000-00000000000c";
    private static final String D = "00000000-0000-0000-0000-00000000000d";
    private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final SoftDeleteSweeper sweeper = new SoftDeleteSweeper("s");
    private final SoftDeleteSweeper.Target protect =
            new SoftDeleteSweeper.Target("kf_x", "xid", SweepPolicy.SWEEP_PROTECT_FAILED_KEYS);
    private final SoftDeleteSweeper.Target strict =
            new SoftDeleteSweeper.Target("kf_x", "xid", SweepPolicy.SWEEP_UNLESS_ANY_FAILURE);

    /** One row's soft-delete state. */
    private record Row(boolean deleted, Instant deletedAt) { }

    /** In-memory table + JDBC proxies that run the sweeper's two statements, with commit / rollback. */
    private static final class FakeDb {
        Map<String, Row> committed = new LinkedHashMap<>();
        Map<String, Row> working;
        boolean autoCommit = true;
        boolean failOnRevive;
        final List<String> executed = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        FakeDb live(String... ids) {
            for (String id : ids) committed.put(id, new Row(false, null));
            return this;
        }

        FakeDb deleted(String id, Instant at) {
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
                            events.add("autoCommit=" + on);
                            yield null;
                        }
                        case "commit" -> {
                            committed = new LinkedHashMap<>(working);
                            events.add("commit");
                            yield null;
                        }
                        case "rollback" -> {
                            working = new LinkedHashMap<>(committed);
                            events.add("rollback");
                            yield null;
                        }
                        case "createArrayOf" -> array((String) a[0], (Object[]) a[1]);
                        case "prepareStatement" -> statement((String) a[0]);
                        default -> defaultFor(m);
                    });
        }

        private Array array(String type, Object[] elements) {
            assertEquals("uuid", type);
            return (Array) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Array.class},
                    (p, m, a) -> m.getName().equals("getArray") ? elements : defaultFor(m));
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
                            executed.add(sql);
                            Set<String> keys = new HashSet<>();
                            for (Object o : (Object[]) ((Array) bound[0]).getArray()) keys.add((String) o);
                            return apply(sql, keys);
                        }
                        return defaultFor(m);
                    });
        }

        private int apply(String sql, Set<String> keys) throws SQLException {
            int n = 0;
            if (sql.equals(SoftDeleteSweeper.markSql("s.kf_x", "xid"))) {
                for (Map.Entry<String, Row> e : view().entrySet()) {
                    if (!keys.contains(e.getKey()) && !e.getValue().deleted()) {
                        e.setValue(new Row(true, NOW));
                        n++;
                    }
                }
            } else if (sql.equals(SoftDeleteSweeper.reviveSql("s.kf_x", "xid"))) {
                if (failOnRevive) throw new SQLException("simulated revive failure");
                for (Map.Entry<String, Row> e : view().entrySet()) {
                    if (keys.contains(e.getKey()) && e.getValue().deleted()) {
                        e.setValue(new Row(false, null));
                        n++;
                    }
                }
            } else {
                throw new SQLException("unexpected SQL: " + sql);
            }
            return n;
        }

        private static Object defaultFor(Method method) {
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return Boolean.FALSE;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            return null;
        }
    }

    /** A full scan that paged to the end and was verified complete, with the given keys seen. */
    private static SweepEvidence verified(String... keys) {
        SweepEvidence e = SweepEvidence.fullScan();
        Arrays.stream(keys).forEach(e::keep);
        e.paginationComplete();
        e.completenessVerified("test: extracted == sourceCount");
        return e;
    }

    @Test
    void marksMissingRowsDeletedAndKeepsPresentOnes() throws Exception {
        FakeDb db = new FakeDb().live(A, B, C);
        SoftDeleteSweeper.Result r = sweeper.sweep(db.connection(), protect, verified(A, B));

        assertTrue(r.swept());
        assertEquals(1, r.marked());
        assertEquals(0, r.revived());
        assertEquals(new Row(true, NOW), db.committed.get(C));
        assertEquals(new Row(false, null), db.committed.get(A));
        assertEquals(List.of("autoCommit=false", "commit", "autoCommit=true"), db.events, "one transaction");
    }

    @Test
    void anAlreadyDeletedRowKeepsItsOriginalDeletedAt() throws Exception {
        FakeDb db = new FakeDb().live(A).deleted(D, EARLIER);
        SoftDeleteSweeper.Result r = sweeper.sweep(db.connection(), protect, verified(A));
        assertEquals(0, r.marked(), "is_deleted = false filter: no re-mark");
        assertEquals(new Row(true, EARLIER), db.committed.get(D), "deleted_at preserved");
    }

    @Test
    void aRowThatReappearsIsRevived() throws Exception {
        FakeDb db = new FakeDb().live(A).deleted(D, EARLIER);
        SoftDeleteSweeper.Result r = sweeper.sweep(db.connection(), protect, verified(A, D));
        assertEquals(1, r.revived());
        assertEquals(new Row(false, null), db.committed.get(D), "is_deleted=false, deleted_at=NULL");
    }

    @Test
    void failedRowKeysAreProtectedFromDeletion() throws Exception {
        FakeDb db = new FakeDb().live(A, B, C);
        SweepEvidence e = verified(A);
        e.rowFailed(B); // B's write failed; it was never kept explicitly
        SoftDeleteSweeper.Result r = sweeper.sweep(db.connection(), protect, e);
        assertTrue(r.swept());
        assertFalse(db.committed.get(B).deleted(), "a failed row is never marked deleted");
        assertTrue(db.committed.get(C).deleted());
        assertEquals(2, r.keepCount());
    }

    @Test
    void theStrictPolicySkipsTheWholeSweepAfterAnyRowFailure() throws Exception {
        FakeDb db = new FakeDb().live(A, B, C);
        SweepEvidence e = verified(A, B);
        e.rowFailed(B);
        SoftDeleteSweeper.Result r = sweeper.sweep(db.connection(), strict, e);
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_ROW_FAILURES, r.status());
        assertTrue(db.executed.isEmpty(), "no SQL at all");
        assertFalse(db.committed.get(C).deleted());

        FakeDb clean = new FakeDb().live(A, B, C);
        assertTrue(sweeper.sweep(clean.connection(), strict, verified(A, B)).swept(), "no failure -> sweeps");
    }

    @Test
    void aFailedRowWithoutAKeyCannotBeProtectedSoTheSweepIsSkipped() throws Exception {
        FakeDb db = new FakeDb().live(A, B);
        SweepEvidence e = verified(A);
        e.rowFailed(null);
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_UNPROTECTABLE_FAILURE,
                sweeper.sweep(db.connection(), protect, e).status());
        assertTrue(db.executed.isEmpty());
    }

    @Test
    void neverSweepsWithoutAVerifiedCompleteFullScan() throws Exception {
        assertSkipped(SoftDeleteSweeper.Status.SKIPPED_INCREMENTAL, incrementalRun());
        assertSkipped(SoftDeleteSweeper.Status.SKIPPED_ABORTED, abortedRun());
        assertSkipped(SoftDeleteSweeper.Status.SKIPPED_PAGINATION_INCOMPLETE, unfinishedPaging());
        assertSkipped(SoftDeleteSweeper.Status.SKIPPED_NOT_VERIFIED, unverified());
        assertSkipped(SoftDeleteSweeper.Status.SKIPPED_COMPLETENESS_FAILED, failedCheck());
    }

    private static SweepEvidence incrementalRun() {
        SweepEvidence e = SweepEvidence.incremental();
        e.keep(A);
        e.paginationComplete();
        e.completenessVerified("would be irrelevant");
        return e;
    }

    private static SweepEvidence abortedRun() {
        SweepEvidence e = verified(A);
        e.abort(new IllegalStateException("HTTP 500 on page 3"));
        return e;
    }

    private static SweepEvidence unfinishedPaging() {
        SweepEvidence e = SweepEvidence.fullScan();
        e.keep(A);
        e.completenessVerified("count matched so far");
        return e;
    }

    private static SweepEvidence unverified() {
        SweepEvidence e = SweepEvidence.fullScan();
        e.keep(A);
        e.paginationComplete();
        return e;
    }

    private static SweepEvidence failedCheck() {
        SweepEvidence e = verified(A);
        e.completenessFailed("extracted 3 != sourceCount 5");
        return e;
    }

    private void assertSkipped(SoftDeleteSweeper.Status expected, SweepEvidence evidence) throws Exception {
        FakeDb db = new FakeDb().live(A, B);
        SoftDeleteSweeper.Result r = sweeper.sweep(db.connection(), protect, evidence);
        assertEquals(expected, r.status());
        assertEquals(0, r.marked());
        assertTrue(db.executed.isEmpty(), expected + ": no SQL executed");
        assertTrue(db.events.isEmpty(), expected + ": no transaction opened");
        assertFalse(db.committed.get(B).deleted());
    }

    @Test
    void anEmptyResponseNeverMarksTheTableDeleted() throws Exception {
        FakeDb db = new FakeDb().live(A, B, C);
        SoftDeleteSweeper.Result r = sweeper.sweep(db.connection(), protect, verified());
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_EMPTY_KEEP, r.status());
        assertTrue(db.executed.isEmpty());
        assertTrue(db.committed.values().stream().noneMatch(Row::deleted));
    }

    @Test
    void aKeyThatIsNotAUuidSkipsTheSweep() throws Exception {
        FakeDb db = new FakeDb().live(A);
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_INVALID_KEY,
                sweeper.sweep(db.connection(), protect, verified(A, "not-a-uuid")).status());
        assertTrue(db.executed.isEmpty());
    }

    @Test
    void aFailureDuringTheSweepRollsBackBothStatements() {
        FakeDb db = new FakeDb().live(A, B, C).deleted(D, EARLIER);
        db.failOnRevive = true;
        assertThrows(SQLException.class, () -> sweeper.sweep(db.connection(), protect, verified(A, D)));
        assertFalse(db.committed.get(B).deleted(), "the mark was rolled back");
        assertEquals(new Row(true, EARLIER), db.committed.get(D));
        assertTrue(db.events.contains("rollback"));
        assertTrue(db.autoCommit, "auto-commit restored");
    }

    @Test
    void everyTableDefaultsToNever() throws Exception {
        for (String table : List.of("kf_identity", "kf_account", "kf_role", "kf_task_result", "kf_workgroup_member",
                "kf_syslog_event", "kf_event")) {
            assertEquals(SweepPolicy.NEVER, SweepPolicies.policyFor(table), table);
        }
        FakeDb db = new FakeDb().live(A, B);
        SoftDeleteSweeper.Target never = new SoftDeleteSweeper.Target("kf_x", "xid", SweepPolicies.policyFor("kf_x"));
        SoftDeleteSweeper.Result r = sweeper.sweep(db.connection(), never, verified(A));
        assertEquals(SoftDeleteSweeper.Status.SKIPPED_POLICY_NEVER, r.status());
        assertTrue(db.executed.isEmpty());
    }

    @Test
    void appendOnlyReportAndGovernanceTablesCanNeverBeGivenASweepPolicy() {
        assertEquals(17, SweepPolicies.NEVER_SWEEP_TABLES.size());
        for (String table : SweepPolicies.NEVER_SWEEP_TABLES) {
            for (SweepPolicy p : List.of(SweepPolicy.SWEEP_PROTECT_FAILED_KEYS, SweepPolicy.SWEEP_UNLESS_ANY_FAILURE)) {
                assertThrows(IllegalArgumentException.class, () -> new SoftDeleteSweeper.Target(table, "id", p), table);
            }
            new SoftDeleteSweeper.Target(table, "id", SweepPolicy.NEVER); // NEVER is always allowed
        }
        assertTrue(SweepPolicies.isNeverSweepTable("kf_syslog_event"));
        assertTrue(SweepPolicies.isNeverSweepTable("kf_entitlement_assignment"));
        assertTrue(SweepPolicies.isNeverSweepTable("kf_extraction_watermark"));
        assertFalse(SweepPolicies.isNeverSweepTable("kf_identity"));
    }

    @Test
    void targetsRequireAPolicyAndSafeIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new SoftDeleteSweeper.Target("kf_x", "xid", null));
        assertThrows(IllegalArgumentException.class,
                () -> new SoftDeleteSweeper.Target("kf_x; drop table y", "xid", SweepPolicy.NEVER));
        assertThrows(IllegalArgumentException.class, () -> new SoftDeleteSweeper.Target("kf_x", "x id", SweepPolicy.NEVER));
        assertThrows(IllegalArgumentException.class, () -> SweepEvidence.fullScan().completenessVerified(" "),
                "a verification must state how completeness was proven");
    }

    @Test
    void sqlMatchesPart1() {
        assertEquals("UPDATE s.kf_identity SET is_deleted = true, deleted_at = now() "
                + "WHERE NOT (userid = ANY(?)) AND is_deleted = false", SoftDeleteSweeper.markSql("s.kf_identity", "userid"));
        assertEquals("UPDATE s.kf_identity SET is_deleted = false, deleted_at = NULL "
                + "WHERE userid = ANY(?) AND is_deleted = true", SoftDeleteSweeper.reviveSql("s.kf_identity", "userid"));
    }

    @Test
    void blankKeysAreIgnoredAndDuplicatesCollapse() {
        SweepEvidence e = verified(A, A, " ", null);
        e.rowFailed(A);
        assertEquals(Set.of(A), SoftDeleteSweeper.protectedKeys(e));
        assertNull(e.completenessFailure());
    }
}

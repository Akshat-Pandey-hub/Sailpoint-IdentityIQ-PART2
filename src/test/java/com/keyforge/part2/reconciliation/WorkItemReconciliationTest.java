package com.keyforge.part2.reconciliation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkItemReconciliation} (Part 1 port): Part 1's SQL / determination assertions, plus an end-to-end pass over a
 * fake connection that answers the column checks, counts and samples and captures the persisted finding.
 */
class WorkItemReconciliationTest {

    private static final String S = "iiq_native_part2";

    // ---- Part 1's assertions (iiq.reconciliation.WorkItemReconciliationTest) -----------------------------

    @Test
    void countSqlIsSchemaQualified() {
        assertEquals("SELECT count(*) FROM iiq_native_part2.kf_workitem",
                WorkItemReconciliation.countSql(S, WorkItemReconciliation.WORKITEM));
        assertEquals("SELECT count(*) FROM iiq_native_part2.kf_workitem_archive",
                WorkItemReconciliation.countSql(S, WorkItemReconciliation.ARCHIVE));
    }

    @Test
    void distinctRefsSqlExcludesNullAndBlank() {
        String sql = WorkItemReconciliation.distinctRefsSql(S);
        assertTrue(sql.contains("count(DISTINCT work_item_id)"), sql);
        assertTrue(sql.contains("iiq_native_part2.kf_identity_request_approval"), sql);
        assertTrue(sql.contains("work_item_id IS NOT NULL AND work_item_id <> ''"), sql);
    }

    @Test
    void matchingUsesExplicitIdColumnsOnly() {
        String either = WorkItemReconciliation.matchedEitherSql(S);
        assertTrue(either.contains("w.source_id = r.wid") && either.contains("a.work_item_id = r.wid"), either);
        assertTrue(either.contains(" OR EXISTS"), either);
        assertTrue(WorkItemReconciliation.matchedLiveSql(S).contains("w.source_id = r.wid"));
        assertTrue(!WorkItemReconciliation.matchedLiveSql(S).contains("kf_workitem_archive"));
        // rows marked deleted are gone from IIQ: neither a live work item nor an approval to reconcile
        assertTrue(WorkItemReconciliation.matchedLiveSql(S).contains("AND NOT w.is_deleted"));
        assertTrue(WorkItemReconciliation.distinctRefsSql(S).contains("AND NOT is_deleted"));
        assertTrue(WorkItemReconciliation.liveWorkItemsSql(S).endsWith("WHERE NOT is_deleted"));
        assertTrue(WorkItemReconciliation.matchedArchiveSql(S).contains("a.work_item_id = r.wid"));
        String samples = WorkItemReconciliation.unmatchedSamplesSql(S, 25);
        assertTrue(samples.contains("NOT EXISTS (SELECT 1 FROM iiq_native_part2.kf_workitem "), samples);
        assertTrue(samples.contains("NOT EXISTS (SELECT 1 FROM iiq_native_part2.kf_workitem_archive "), samples);
        assertTrue(samples.contains("LIMIT 25"), samples);
    }

    @Test
    void determinationIsNeutralAndNeverAssertsACause() {
        WorkItemReconciliation.Result r = new WorkItemReconciliation.Result();
        r.approvalRefs = 11;
        r.unmatched = 11;
        String d = WorkItemReconciliation.buildDetermination(r);
        assertTrue(d.contains("do not resolve to a live WorkItem or WorkItemArchive record"), d);
        assertTrue(d.contains("zero WorkItemArchive records"), d);
        assertTrue(!d.toLowerCase().contains("prune") && !d.toLowerCase().contains("housekeeping"), d);

        WorkItemReconciliation.Result matched = new WorkItemReconciliation.Result();
        matched.approvalRefs = 5;
        assertTrue(WorkItemReconciliation.buildDetermination(matched).contains("resolve to a live or archived WorkItem"));
        assertTrue(WorkItemReconciliation.buildDetermination(new WorkItemReconciliation.Result())
                .contains("nothing to reconcile"));
    }

    // ---- end to end over a fake connection ---------------------------------------------------------------

    /** Answers information_schema lookups, scalar counts and the sample query; captures every write. */
    private static final class Db {
        final Set<String> columns = new HashSet<>(Set.of("kf_identity_request_approval.work_item_id",
                "kf_workitem.source_id", "kf_workitem_archive.work_item_id"));
        final Map<String, Long> scalars = new HashMap<>();
        final List<String> samples = new ArrayList<>();
        final List<String> statements = new ArrayList<>();
        final List<Map<Integer, Object>> upserts = new ArrayList<>();
        boolean failUpsert;

        Db counts(long live, long archives, long refs, long matchedLive, long matchedArchive, long matchedEither) {
            scalars.put(WorkItemReconciliation.liveWorkItemsSql(S), live);
            scalars.put(WorkItemReconciliation.countSql(S, WorkItemReconciliation.ARCHIVE), archives);
            scalars.put(WorkItemReconciliation.distinctRefsSql(S), refs);
            scalars.put(WorkItemReconciliation.matchedLiveSql(S), matchedLive);
            scalars.put(WorkItemReconciliation.matchedArchiveSql(S), matchedArchive);
            scalars.put(WorkItemReconciliation.matchedEitherSql(S), matchedEither);
            return this;
        }

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
                        if (m.getName().equals("execute")) {
                            statements.add((String) a[0]);
                            return false;
                        }
                        if (m.getName().equals("executeQuery")) {
                            String sql = (String) a[0];
                            statements.add(sql);
                            if (sql.startsWith("SELECT r.wid")) {
                                return rows(new ArrayList<>(samples));
                            }
                            Long v = scalars.get(sql);
                            if (v == null) throw new SQLException("unexpected query " + sql);
                            return rows(List.of(v));
                        }
                        return defaultFor(m);
                    });
        }

        private PreparedStatement prepared(String sql) {
            Map<Integer, Object> bound = new LinkedHashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "setObject", "setString", "setLong", "setTimestamp" -> bound.put((Integer) a[0], a[1]);
                            case "setNull" -> bound.put((Integer) a[0], "NULL");
                            case "executeQuery" -> {
                                String key = bound.get(2) + "." + bound.get(3); // information_schema lookup
                                return rows(columns.contains(key) ? List.of(1) : List.of());
                            }
                            case "executeUpdate" -> {
                                statements.add(sql);
                                if (failUpsert) throw new SQLException("relation is read-only");
                                upserts.add(new LinkedHashMap<>(bound));
                                return 1;
                            }
                            default -> { }
                        }
                        return defaultFor(m);
                    });
        }

        private static ResultSet rows(List<?> values) {
            int[] i = {-1};
            return (ResultSet) Proxy.newProxyInstance(Db.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "next" -> ++i[0] < values.size();
                        case "getLong" -> ((Number) values.get(i[0])).longValue();
                        case "getString" -> String.valueOf(values.get(i[0]));
                        default -> defaultFor(m);
                    });
        }

        private static Object defaultFor(Method m) {
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return Boolean.FALSE;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            return null;
        }
    }

    @Test
    void unmatchedReferencesAreRecordedAsOneInfoFindingForTheRun() throws Exception {
        // the shape of the stored Part 1 finding: 5 live items, 0 archives, 11 references, none matched
        Db db = new Db().counts(5, 0, 11, 0, 0, 0);
        db.samples.addAll(List.of("7f0001019f061fdc819f06e6cbf30030", "7f0001019f061fdc819f06e9f9a30039"));
        WorkItemReconciliation.Result r = new WorkItemReconciliation(S).reconcile(db.connection(), "run-1");

        assertEquals(11, r.unmatched);
        assertEquals(ReconciliationFindingRepository.findingId("run-1", "approval_workitem_reference"), r.findingId);
        assertEquals(1, db.upserts.size());
        Map<Integer, Object> f = db.upserts.get(0);
        assertEquals("run-1", f.get(2), "run_id = the kf_extraction_run id");
        assertEquals("approval_workitem_reference", f.get(3));
        assertEquals("kf_identity_request_approval", f.get(4));
        assertEquals("work_item_id", f.get(5));
        assertEquals("kf_workitem|kf_workitem_archive", f.get(6));
        assertEquals("source_id|work_item_id", f.get(7));
        assertEquals("CHECKED", f.get(8));
        assertEquals(11L, f.get(10));
        assertEquals("[\"7f0001019f061fdc819f06e6cbf30030\",\"7f0001019f061fdc819f06e9f9a30039\"]", f.get(11));
        assertEquals("INFO", f.get(12));
        assertTrue(((String) f.get(13)).startsWith("11 approval WorkItem reference(s) do not resolve"));
    }

    @Test
    void fullyMatchedReferencesAreRecordedAsOk() throws Exception {
        Db db = new Db().counts(5, 2, 4, 3, 1, 4);
        WorkItemReconciliation.Result r = new WorkItemReconciliation(S).reconcile(db.connection(), "run-2");
        assertEquals(0, r.unmatched);
        Map<Integer, Object> f = db.upserts.get(0);
        assertEquals(0L, f.get(10));
        assertEquals("OK", f.get(12));
        assertEquals("[]", f.get(11));
        assertTrue(db.statements.stream().noneMatch(s -> s.startsWith("SELECT r.wid")), "no samples when none unmatched");
    }

    @Test
    void aMissingTableIsRecordedAsSkippedNeverAsAFalseZero() throws Exception {
        Db db = new Db();
        db.columns.remove("kf_workitem_archive.work_item_id");
        WorkItemReconciliation.Result r = new WorkItemReconciliation(S).reconcile(db.connection(), "run-3");
        assertTrue(r.skipped);
        assertTrue(r.skipReason.contains("kf_workitem_archive.work_item_id (run import-workitem-archive)"), r.skipReason);
        Map<Integer, Object> f = db.upserts.get(0);
        assertEquals("SKIPPED", f.get(8));
        assertEquals("NULL", f.get(10), "orphan_count NULL when not evaluated");
        assertEquals("SKIPPED", f.get(12));
        assertTrue(db.statements.stream().noneMatch(s -> s.startsWith("SELECT count")), "no counts when skipped");
    }

    @Test
    void repeatedRunsAddOneFindingEachAndARerunOfARunUpdatesIt() throws Exception {
        Db db = new Db().counts(5, 0, 11, 0, 0, 0);
        WorkItemReconciliation svc = new WorkItemReconciliation(S);
        var first = svc.reconcile(db.connection(), "run-a");
        var again = svc.reconcile(db.connection(), "run-a");
        var next = svc.reconcile(db.connection(), "run-b");
        assertEquals(first.findingId, again.findingId, "same run -> same finding id (upsert, no duplicate)");
        assertNotEquals(first.findingId, next.findingId, "a later run records its own finding");
        assertTrue(db.statements.stream().filter(s -> s.startsWith("INSERT"))
                .allMatch(s -> s.contains("ON CONFLICT (finding_id) DO UPDATE")));
    }

    @Test
    void itWritesOnlyTheFindingsTable() throws Exception {
        Db db = new Db().counts(5, 0, 11, 0, 0, 0);
        new WorkItemReconciliation(S).reconcile(db.connection(), "run-4");
        for (String sql : db.statements) {
            String upper = sql.toUpperCase();
            if (upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("DELETE")
                    || upper.startsWith("ALTER")) {
                assertTrue(sql.contains("iiq_native_part2.kf_reconciliation_finding"), sql);
            }
        }
    }

    @Test
    void aDatabaseErrorPropagates() {
        Db db = new Db().counts(5, 0, 11, 0, 0, 0);
        db.failUpsert = true;
        assertThrows(SQLException.class, () -> new WorkItemReconciliation(S).reconcile(db.connection(), "run-5"));

        Db broken = new Db().counts(5, 0, 11, 0, 0, 0);
        broken.scalars.remove(WorkItemReconciliation.matchedEitherSql(S));
        assertThrows(SQLException.class, () -> new WorkItemReconciliation(S).reconcile(broken.connection(), "run-6"));
        assertTrue(broken.upserts.isEmpty(), "no finding is persisted from a failed evaluation");
    }
}

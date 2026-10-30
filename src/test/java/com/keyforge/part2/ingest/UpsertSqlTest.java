package com.keyforge.part2.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4 / D3: the "write only when something changed" SQL. Rows are compared on their data columns (never the per-run
 * lineage), soft-deletable tables also revive a deleted row, and the comparison never relies on a partial hash.
 */
class UpsertSqlTest {

    @Test
    void tailComparesDataColumnsAndSetsLineage() {
        String tail = UpsertSql.tail("xid", List.of("a", "b"), List.of("source_system", "extraction_run_id"), true);
        assertEquals("ON CONFLICT (xid) DO UPDATE SET a = EXCLUDED.a, b = EXCLUDED.b, "
                + "source_system = EXCLUDED.source_system, extraction_run_id = EXCLUDED.extraction_run_id, "
                + "extracted_at = now(), is_deleted = false, deleted_at = null "
                + "WHERE ROW(cur.a, cur.b) IS DISTINCT FROM ROW(EXCLUDED.a, EXCLUDED.b) OR cur.is_deleted "
                + "RETURNING (xmax = 0)", tail);
        String history = UpsertSql.tail("xid", List.of("a"), List.of("extraction_run_id"), false);
        assertFalse(history.contains("is_deleted"), "history tables have no soft delete");
    }

    @Test
    void entityTablesCompareEveryBusinessColumnNotThePartialHash() {
        // Part 1's entitlement hash leaves 10 columns out; a change to one of them must still be written
        String sql = new EntityRepository("s", EntitlementTable.SPEC).upsertSql();
        String where = sql.substring(sql.indexOf(" WHERE ROW("));
        for (ColumnSpec c : EntitlementTable.SPEC.allBusiness()) {
            assertTrue(where.contains("cur." + c.name + ",") || where.contains("cur." + c.name + ")"), c.name);
        }
        assertTrue(where.contains("cur.is_inactive"), "a column the entitlement record_hash ignores");
        assertFalse(where.contains("record_hash"), "the hash is not the change test");
        assertFalse(where.contains("extraction_run_id"), "a new run alone never rewrites a row");
        assertTrue(where.contains(" OR cur.is_deleted"), "a soft-deleted row is revived");
        assertTrue(sql.startsWith("INSERT INTO s.kf_entitlement AS cur ("));
    }

    @Test
    void customCurrentStateWritersAlsoSkipUnchangedRows() {
        for (String sql : List.of(new RoleEntitlementRepository("s").upsertSql(), new RoleHierarchyRepository("s").upsertSql(),
                new WorkgroupMemberRepository("s").upsertSql(), new WorkflowDefinitionRepository("s").upsertSql())) {
            assertTrue(sql.contains(") IS DISTINCT FROM ROW(") && sql.contains(" OR cur.is_deleted"), sql);
            assertFalse(sql.substring(sql.indexOf(" WHERE ROW(")).contains("extraction_run_id"), sql);
        }
    }
}

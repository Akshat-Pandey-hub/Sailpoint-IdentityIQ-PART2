package com.keyforge.part2.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the generated DDL + upsert SQL match the established kf_identity contract (no live DB needed). */
class IdentitySqlTest {

    private final IdentityRepository repo = new IdentityRepository("iiq_native_part2");

    @Test
    void createTableHasUuidPkMetadataAndNoRecordHash() {
        String ddl = repo.createTableSql();
        assertTrue(ddl.contains("iiq_native_part2.kf_identity"));
        assertTrue(ddl.contains("userid uuid PRIMARY KEY"));
        // metadata columns present
        assertTrue(ddl.contains("source_system text"));
        assertTrue(ddl.contains("source_interface text"));
        assertTrue(ddl.contains("source_object_type text"));
        assertTrue(ddl.contains("extraction_run_id text"));
        assertTrue(ddl.contains("extracted_at timestamp with time zone NOT NULL DEFAULT now()"));
        assertTrue(ddl.contains("is_deleted boolean NOT NULL DEFAULT false"));
        assertTrue(ddl.contains("deleted_at timestamp with time zone"));
        // a few business columns with the right types
        assertTrue(ddl.contains("inactive boolean"));
        assertTrue(ddl.contains("attributes jsonb"));
        assertTrue(ddl.contains("created_at timestamp with time zone"));
        // kf_identity must NOT have record_hash
        assertFalse(ddl.contains("record_hash"), "kf_identity has no record_hash column");
    }

    @Test
    void upsertConflictsOnUseridAndReturnsInsertFlag() {
        String sql = repo.upsertSql();
        assertTrue(sql.startsWith("INSERT INTO iiq_native_part2.kf_identity ("));
        assertTrue(sql.contains("ON CONFLICT (userid) DO UPDATE SET"));
        assertTrue(sql.contains("RETURNING (xmax = 0)"));
        // typed casts present
        assertTrue(sql.contains("?::uuid"));
        assertTrue(sql.contains("?::jsonb"));
        assertTrue(sql.contains("?::timestamptz"));
        // generated-at-write literals, not params
        assertTrue(sql.contains("now()"));
        assertTrue(sql.contains("is_deleted"));
        // update must refresh liveness
        assertTrue(sql.contains("is_deleted = false"));
        assertTrue(sql.contains("deleted_at = null"));
        assertTrue(sql.contains("extracted_at = now()"));
    }

    @Test
    void columnCountEqualsPlaceholderCount() {
        String sql = repo.upsertSql();
        String cols = sql.substring(sql.indexOf('(') + 1, sql.indexOf(") VALUES"));
        String vals = sql.substring(sql.indexOf("VALUES (") + "VALUES (".length(), sql.indexOf(") ON CONFLICT"));
        int nCols = cols.split(",").length;
        int nVals = vals.split(",").length;
        assertEquals(nCols, nVals, "column list and VALUES list must have equal length");
        // userid + 41 business + 4 metadata + extracted_at/is_deleted/deleted_at = 49 columns
        assertEquals(1 + IdentityTable.BUSINESS.size() + 4 + 3, nCols);
    }

    @Test
    void parameterPlaceholdersMatchBoundParameters() {
        // bound params = userid(1) + each business column(1) + 4 metadata text params.
        // (extracted_at/is_deleted/deleted_at are literals, not params.)
        String sql = repo.upsertSql();
        String vals = sql.substring(sql.indexOf("VALUES (") + "VALUES (".length(), sql.indexOf(") ON CONFLICT"));
        long qMarks = vals.chars().filter(ch -> ch == '?').count();
        assertEquals(1 + IdentityTable.BUSINESS.size() + 4, qMarks, "? count = bound params");
    }
}

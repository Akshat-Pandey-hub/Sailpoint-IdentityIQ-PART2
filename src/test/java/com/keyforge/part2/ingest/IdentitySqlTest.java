package com.keyforge.part2.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies generated identity DDL and conditional unchanged-row upsert SQL without a live DB. */
class IdentitySqlTest {

    private final IdentityRepository repo = new IdentityRepository("iiq_native_part2");

    @Test
    void createTableHasUuidPkMetadataAndRecordHash() {
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
        assertTrue(ddl.contains("record_hash text"));
    }

    @Test
    void upsertConflictsOnUseridAndReturnsInsertFlag() {
        String sql = repo.upsertSql();
        assertTrue(sql.startsWith("INSERT INTO iiq_native_part2.kf_identity AS identity_row ("));
        assertTrue(sql.contains("ON CONFLICT (userid) DO UPDATE SET"));
        assertTrue(sql.contains("WHERE identity_row.record_hash IS DISTINCT FROM EXCLUDED.record_hash OR identity_row.is_deleted"));
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
        // userid + business + record_hash + 4 metadata + extracted_at/is_deleted/deleted_at.
        assertEquals(1 + IdentityTable.BUSINESS.size() + 1 + 4 + 3, nCols);
    }

    @Test
    void parameterPlaceholdersMatchBoundParameters() {
        // bound params = userid(1) + business + record_hash + 4 metadata text params.
        // (extracted_at/is_deleted/deleted_at are literals, not params.)
        String sql = repo.upsertSql();
        String vals = sql.substring(sql.indexOf("VALUES (") + "VALUES (".length(), sql.indexOf(") ON CONFLICT"));
        long qMarks = vals.chars().filter(ch -> ch == '?').count();
        assertEquals(1 + IdentityTable.BUSINESS.size() + 1 + 4, qMarks, "? count = bound params");
    }
}

package com.keyforge.part2.ledger;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RunLedgerRepository}: Part 1's kf_extraction_run DDL (column for column) and an idempotent insert. */
class RunLedgerRepositoryTest {

    /** iiq_native.kf_extraction_run column order (Part 1 dump). */
    private static final List<String> PART1_COLUMNS = List.of(
            "extraction_run_id", "command", "source_interface", "status", "started_at", "ended_at", "duration_ms",
            "window_start", "window_end", "extracted", "inserted", "updated", "failed", "entity_counts",
            "error_message", "recorded_at");

    private final RunLedgerRepository repo = new RunLedgerRepository("iiq_native_part2");

    @Test
    void ddlMatchesPart1() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String part : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(", ")) {
            cols.add(part.trim().split("\\s+")[0]);
        }
        assertEquals(PART1_COLUMNS, cols);
        assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS iiq_native_part2.kf_extraction_run ("));
        assertTrue(ddl.contains("extraction_run_id uuid PRIMARY KEY") && ddl.contains("entity_counts jsonb"));
        assertTrue(repo.insertSql().endsWith("ON CONFLICT (extraction_run_id) DO NOTHING"), "a retried insert is a no-op");
    }

    @Test
    void insertBindsEveryField() throws Exception {
        Map<Integer, Object> bound = new LinkedHashMap<>();
        int[] executed = {0};
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "setString", "setTimestamp", "setLong", "setInt" -> bound.put((Integer) a[0], a[1]);
                        case "setNull" -> bound.put((Integer) a[0], "NULL");
                        case "executeUpdate" -> {
                            executed[0]++;
                            return 1;
                        }
                        default -> { }
                    }
                    return null;
                });
        Connection conn = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> m.getName().equals("prepareStatement") ? ps : null);

        Instant start = Instant.parse("2026-10-12T10:00:00Z");
        Instant end = Instant.parse("2026-10-12T10:00:05Z");
        repo.insert(conn, new ExtractionRun("00000000-0000-0000-0000-0000000000aa", "import-role", "kfagent-rest",
                "SUCCESS", start, end, 5000L, null, null, 4, 0, 4, 0, "[{\"entity\":\"kf_role\"}]", null));

        assertEquals(1, executed[0]);
        assertEquals("00000000-0000-0000-0000-0000000000aa", bound.get(1));
        assertEquals("import-role", bound.get(2));
        assertEquals("kfagent-rest", bound.get(3));
        assertEquals("SUCCESS", bound.get(4));
        assertEquals(Timestamp.from(start), bound.get(5));
        assertEquals(Timestamp.from(end), bound.get(6));
        assertEquals(5000L, bound.get(7));
        assertEquals("NULL", bound.get(8), "no window for a full import");
        assertEquals("NULL", bound.get(9));
        assertEquals(4, bound.get(10));
        assertEquals(4, bound.get(12));
        assertEquals("[{\"entity\":\"kf_role\"}]", bound.get(14));
        assertEquals(null, bound.get(15), "no error");
        assertEquals(15, bound.size());
    }
}

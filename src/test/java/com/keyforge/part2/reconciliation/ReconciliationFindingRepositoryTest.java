package com.keyforge.part2.reconciliation;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ReconciliationFindingRepository}: Part 1's table, Part 1's finding ids (from stored rows), the upsert. */
class ReconciliationFindingRepositoryTest {

    private final ReconciliationFindingRepository repo = new ReconciliationFindingRepository("iiq_native_part2");

    @Test
    void tableMatchesPart1ColumnForColumn() {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String part : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(", ")) {
            cols.add(part.trim().split("\\s+")[0]);
        }
        assertEquals(List.of("finding_id", "run_id", "check_name", "child_table", "child_column", "parent_table",
                "parent_column", "status", "skip_reason", "orphan_count", "sample_ids", "severity", "detected_at",
                "note"), cols, "iiq_native.kf_reconciliation_finding column order");
        assertTrue(ddl.contains("finding_id uuid PRIMARY KEY") && ddl.contains("sample_ids jsonb"));
        assertTrue(repo.upsertSql().contains("ON CONFLICT (finding_id) DO UPDATE SET"));
    }

    @Test
    void findingIdsReproducePart1sStoredRows() {
        // the three approval_workitem_reference findings stored in iiq_native.kf_reconciliation_finding
        assertEquals(UUID.fromString("54aa5217-b01a-3652-a132-1473d1811dac"), ReconciliationFindingRepository
                .findingId("4765b960-d423-49ba-95c4-f6720e467751", "approval_workitem_reference"));
        assertEquals(UUID.fromString("525deba6-a3c6-323c-a0ee-8dcee180517c"), ReconciliationFindingRepository
                .findingId("1d012483-e1a7-4dc0-ba98-4de8e72549cd", "approval_workitem_reference"));
        assertEquals(UUID.fromString("a87e1ade-7b74-3cd8-9360-524e7daf6976"), ReconciliationFindingRepository
                .findingId("229eee15-2b1c-4584-8f4b-5c26139b038d", "approval_workitem_reference"));
    }

    @Test
    void oneFindingPerRunAndCheck() {
        UUID a = ReconciliationFindingRepository.findingId("run-1", "approval_workitem_reference");
        assertEquals(a, ReconciliationFindingRepository.findingId("run-1", "approval_workitem_reference"),
                "the same run re-running the check updates the same finding");
        assertNotEquals(a, ReconciliationFindingRepository.findingId("run-2", "approval_workitem_reference"),
                "a new run records its own finding");
        assertNotEquals(a, ReconciliationFindingRepository.findingId("run-1", "other_check"));
    }

    @Test
    void upsertBindsEveryColumn() throws Exception {
        Map<Integer, Object> bound = new LinkedHashMap<>();
        Connection conn = connection(bound);
        Instant at = Instant.parse("2026-10-12T12:00:00Z");
        UUID id = repo.upsert(conn, "run-9", new ReconciliationFindingRepository.Finding("approval_workitem_reference",
                "kf_identity_request_approval", "work_item_id", "kf_workitem|kf_workitem_archive",
                "source_id|work_item_id", "CHECKED", null, 2L, List.of("w1", "w\"2"), "INFO", "2 do not resolve"), at);

        assertEquals(ReconciliationFindingRepository.findingId("run-9", "approval_workitem_reference"), id);
        assertEquals(id, bound.get(1));
        assertEquals("run-9", bound.get(2), "run_id = the kf_extraction_run id");
        assertEquals("approval_workitem_reference", bound.get(3));
        assertEquals("CHECKED", bound.get(8));
        assertEquals(2L, bound.get(10));
        assertEquals("[\"w1\",\"w\\\"2\"]", bound.get(11), "sample ids JSON, quotes escaped");
        assertEquals("INFO", bound.get(12));
        assertEquals("2 do not resolve", bound.get(13));
        assertEquals(Timestamp.from(at), bound.get(14));
        assertEquals(14, bound.size());

        Map<Integer, Object> skipped = new LinkedHashMap<>();
        repo.upsert(connection(skipped), "run-9", new ReconciliationFindingRepository.Finding("c", "t", "c", "p", "c",
                "SKIPPED", "absent", null, List.of(), "SKIPPED", "skipped"), at);
        assertEquals("NULL", skipped.get(10), "SKIPPED -> orphan_count NULL (never a false zero)");
        assertEquals("[]", skipped.get(11));
    }

    @Test
    void aFindingWithoutARunIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> repo.upsert(connection(new LinkedHashMap<>()), " ",
                new ReconciliationFindingRepository.Finding("c", "t", "c", "p", "c", "CHECKED", null, 0L, List.of(),
                        "OK", "n"), Instant.now()));
    }

    private Connection connection(Map<Integer, Object> bound) {
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "setObject", "setString", "setLong", "setTimestamp" -> bound.put((Integer) a[0], a[1]);
                        case "setNull" -> bound.put((Integer) a[0], "NULL");
                        case "executeUpdate" -> {
                            return 1;
                        }
                        default -> { }
                    }
                    return null;
                });
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> m.getName().equals("prepareStatement") ? ps : null);
    }
}

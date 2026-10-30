package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four SQL-report tables as keyed current-state tables: each one's DDL (uuid key, the report's SELECT columns as
 * text in Part 1's order, then the lineage columns), its key columns, and the row uuid derived from them (stable,
 * unambiguous, distinct for every row of Part 1's real sample pages).
 */
class SqlReportTablesTest {

    private static final List<String> LINEAGE = List.of("record_hash", "source_system", "source_interface",
            "source_object_type", "src_natural_key", "extraction_run_id", "extracted_at", "is_deleted", "deleted_at");

    /** Rows of Part 1's /kfagent/workgroup-members as served (several workgroups without members). */
    private static final String WORKGROUP_PAGE = "["
            + "{\"workgroup_name\":\"AdminCap\",\"user_name\":\"spadmin\",\"display_name\":\"Molly J\",\"first_name\":\"Molly\","
            + "\"last_name\":\"J\",\"email\":\"m@example.com\",\"user_status\":\"Active\"},"
            + "{\"workgroup_name\":\"Adminq\",\"user_name\":\"spadmin\",\"display_name\":\"Molly J\",\"first_name\":\"Molly\","
            + "\"last_name\":\"J\",\"email\":\"m@example.com\",\"user_status\":\"Active\"},"
            + "{\"workgroup_name\":\"AttributeSyncWorkGroup\",\"user_name\":null,\"display_name\":null,\"first_name\":null,"
            + "\"last_name\":null,\"email\":null,\"user_status\":\"No Members\"},"
            + "{\"workgroup_name\":\"RapidSetup No Manager\",\"user_name\":null,\"display_name\":null,\"first_name\":null,"
            + "\"last_name\":null,\"email\":null,\"user_status\":\"No Members\"}]";

    /** Rows of Part 1's /kfagent/entitlement-certification-status as served (a role row has NULL application/account). */
    private static final String STATUS_PAGE = "["
            + "{\"user_id\":\"Alexander Bailey\",\"user_name\":\"Alexander Bailey\",\"manager_user_id\":null,\"manager_name\":null,"
            + "\"application_name\":\"EntraAuth\",\"account_name\":\"54f9a344-22a1-4220-aa08-b19f1f8a3633\","
            + "\"entitlement_attribute\":\"groups\",\"entitlement_value\":\"001ec823-2a04-4a63-90b8-3882abffbcfb\","
            + "\"entitlement_assigned_date\":\"2025-07-27 03:27:26.258+00\",\"last_certification_name\":null,"
            + "\"last_certified_date\":null,\"certifier\":null,\"certification_decision\":null,\"never_certified\":\"YES\"},"
            + "{\"user_id\":\"App_IDJ0001000\",\"user_name\":\"Ethan Reyes\",\"manager_user_id\":null,\"manager_name\":null,"
            + "\"application_name\":null,\"account_name\":null,\"entitlement_attribute\":\"assignedRoles\","
            + "\"entitlement_value\":\"Engineering-Base\",\"entitlement_assigned_date\":\"2026-08-03 15:20:36.181+00\","
            + "\"last_certification_name\":null,\"last_certified_date\":null,\"certifier\":null,"
            + "\"certification_decision\":null,\"never_certified\":\"YES\"},"
            + "{\"user_id\":\"Alexander Evans\",\"user_name\":\"Alexander Evans\",\"manager_user_id\":null,\"manager_name\":null,"
            + "\"application_name\":\"EntraTarget\",\"account_name\":\"179fcc53-99af-43e1-92c6-38c46c6af4ee\","
            + "\"entitlement_attribute\":\"groups\",\"entitlement_value\":\"00edc4ac-9a67-4e7e-abe3-f84d9e8ffee5\","
            + "\"entitlement_assigned_date\":\"2025-07-27 03:35:00.403+00\","
            + "\"last_certification_name\":\"Targeted Access Review for Alexander Evans\","
            + "\"last_certified_date\":\"2026-10-02 15:23:51.988+00\",\"certifier\":\"spadmin\","
            + "\"certification_decision\":\"Approved\",\"never_certified\":\"NO\"}]";

    private final ObjectMapper mapper = new ObjectMapper();

    private static List<String> ddlColumns(SqlReportRepository repo) {
        String ddl = repo.createTableSql();
        List<String> cols = new ArrayList<>();
        for (String line : ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')')).split(",\n")) {
            cols.add(line.trim().split("\\s+")[0]);
        }
        return cols;
    }

    @Test
    void everyReportTableHasAUuidKeyItsColumnsAndTheLineageColumns() {
        for (SqlReportSpec spec : List.of(EntitlementAssignmentReport.SPEC, EntitlementCertificationReport.SPEC,
                EntitlementCertificationStatusReport.SPEC, WorkgroupMembersReport.SPEC)) {
            SqlReportRepository repo = new SqlReportRepository("iiq_native_part2_test", spec);
            List<String> expected = new ArrayList<>();
            expected.add(spec.pkColumn());
            expected.addAll(spec.columns());
            expected.addAll(LINEAGE);
            assertEquals(expected, ddlColumns(repo), spec.table());
            String ddl = repo.createTableSql();
            assertTrue(ddl.contains(spec.pkColumn() + " uuid PRIMARY KEY"), spec.table());
            assertFalse(ddl.contains("GENERATED ALWAYS AS IDENTITY"), "no row numbers: " + spec.table());
            assertTrue(spec.columns().containsAll(spec.keyColumns()), spec.table());
            assertTrue(repo.upsertSql().contains("ON CONFLICT (" + spec.pkColumn() + ") DO UPDATE"), spec.table());
            assertTrue(repo.upsertSql().contains("record_hash IS DISTINCT FROM EXCLUDED.record_hash"),
                    "an unchanged live row is skipped: " + spec.table());
        }
        assertEquals(29, EntitlementAssignmentReport.COLUMNS.size());
        assertEquals(9, EntitlementCertificationReport.COLUMNS.size());
        assertEquals(14, EntitlementCertificationStatusReport.COLUMNS.size());
        assertEquals(7, WorkgroupMembersReport.COLUMNS.size());
    }

    @Test
    void theRowUuidComesFromTheKeyColumnsOnly() throws Exception {
        SqlReportRepository repo = new SqlReportRepository("s", WorkgroupMembersReport.SPEC);
        JsonNode active = mapper.readTree("{\"workgroup_name\":\"AdminCap\",\"user_name\":\"spadmin\",\"user_status\":\"Active\"}");
        JsonNode disabled = mapper.readTree("{\"workgroup_name\":\"AdminCap\",\"user_name\":\"spadmin\",\"user_status\":\"Disabled\"}");
        assertEquals(repo.key(active), repo.key(disabled), "same member of the same workgroup -> same row");
        assertNotEquals(repo.recordHash(active), repo.recordHash(disabled), "...whose changed status is an update");
        assertEquals(repo.key(active), new SqlReportRepository("other", WorkgroupMembersReport.SPEC).key(active),
                "the uuid does not depend on the schema or the run");
        assertEquals("8:AdminCap|7:spadmin", repo.naturalKey(active));
    }

    @Test
    void theNaturalKeyIsUnambiguous() throws Exception {
        SqlReportRepository repo = new SqlReportRepository("s", WorkgroupMembersReport.SPEC);
        JsonNode a = mapper.readTree("{\"workgroup_name\":\"a|b\",\"user_name\":\"c\"}");
        JsonNode b = mapper.readTree("{\"workgroup_name\":\"a\",\"user_name\":\"b|c\"}");
        JsonNode nullMember = mapper.readTree("{\"workgroup_name\":\"a\",\"user_name\":null}");
        JsonNode textNull = mapper.readTree("{\"workgroup_name\":\"a\",\"user_name\":\"-\"}");
        assertNotEquals(repo.key(a), repo.key(b), "a | inside a value never merges two rows");
        assertNotEquals(repo.key(nullMember), repo.key(textNull), "a NULL never equals a text value");
        assertNotEquals(repo.key(a), new SqlReportRepository("s", EntitlementCertificationReport.SPEC).key(a),
                "the report id is part of the seed");
    }

    @Test
    void partOneSamplePagesGiveEveryRowItsOwnUuid() throws Exception {
        assertDistinctKeys(WorkgroupMembersReport.SPEC, WORKGROUP_PAGE);
        assertDistinctKeys(EntitlementCertificationStatusReport.SPEC, STATUS_PAGE);
    }

    private void assertDistinctKeys(SqlReportSpec spec, String page) throws Exception {
        SqlReportRepository repo = new SqlReportRepository("s", spec);
        Set<String> keys = new HashSet<>();
        int rows = 0;
        for (JsonNode row : mapper.readTree(page)) {
            keys.add(repo.key(row));
            rows++;
        }
        assertEquals(rows, keys.size(), spec.table());
    }

    @Test
    void keyColumnsMustBeReportColumns() {
        assertThrows(IllegalArgumentException.class, () -> new SqlReportSpec("X", "x", "x", "kf_x", "xid",
                List.of("a", "b"), List.of("c")));
        assertThrows(IllegalArgumentException.class, () -> new SqlReportSpec("X", "x", "x", "kf_x", "xid",
                List.of("a", "b"), List.of()));
        assertEquals("sql_report.workgroupMembers", WorkgroupMembersReport.SPEC.sourceObjectType());
    }
}

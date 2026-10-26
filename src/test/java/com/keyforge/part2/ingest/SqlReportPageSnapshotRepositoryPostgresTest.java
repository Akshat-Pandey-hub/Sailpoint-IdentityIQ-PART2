package com.keyforge.part2.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional PostgreSQL regression: set KFAGENT_TEST_PG_URL and optional username/password. */
class SqlReportPageSnapshotRepositoryPostgresTest {

    @Test
    void createsAndWritesAppendOnlySnapshotTables() throws Exception {
        String url = System.getenv("KFAGENT_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "set KFAGENT_TEST_PG_URL to run PostgreSQL behavior test");
        String username = System.getenv("KFAGENT_TEST_PG_USERNAME");
        String password = System.getenv("KFAGENT_TEST_PG_PASSWORD");
        String schema = "kf_sql_page_it_" + UUID.randomUUID().toString().replace("-", "");
        SqlReportPageSnapshotRepository repository = new SqlReportPageSnapshotRepository(schema);
        try {
            try (Connection conn = connection(url, username, password)) {
                repository.ensureSchemaAndTables(conn);
                var stored = repository.store(conn, "WorkgroupMembers", "workgroup-members", "it-run",
                        10, 2, List.of(new ObjectMapper().readTree("{\"user_name\":\"alice\"}")));

                assertEquals(1, stored.rowsStored());
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT s.requested_start, s.requested_limit, s.row_count, "
                             + "r.row_ordinal, r.row_json->>'user_name' FROM " + repository.snapshotTable()
                             + " s JOIN " + repository.rowTable() + " r USING (snapshot_id)")) {
                    org.junit.jupiter.api.Assertions.assertTrue(rs.next());
                    assertEquals(10, rs.getInt(1));
                    assertEquals(2, rs.getInt(2));
                    assertEquals(1, rs.getInt(3));
                    assertEquals(0, rs.getInt(4));
                    assertEquals("alice", rs.getString(5));
                    org.junit.jupiter.api.Assertions.assertFalse(rs.next());
                }
            }
        } finally {
            try (Connection conn = connection(url, username, password); Statement st = conn.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static Connection connection(String url, String username, String password) throws Exception {
        return username == null ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, username, password == null ? "" : password);
    }
}

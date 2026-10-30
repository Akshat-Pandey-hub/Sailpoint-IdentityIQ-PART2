package com.keyforge.part2.ingest;

import com.keyforge.part2.config.AppConfig;
import com.keyforge.part2.http.KfAgentClient;
import com.keyforge.part2.id.CanonicalIds;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional PostgreSQL regression: set KFAGENT_TEST_PG_URL (and optional username/password) to run it. */
class IdentityRepositoryPostgresTest {

    private static final String BASE_JSON = "{\"source_id\":\"7f0001019714176881971476c14808ec\","
            + "\"name\":\"Alice.Admin\",\"display_name\":\"Alice\",\"inactive\":false,"
            + "\"correlated\":true,\"accounts\":[{\"app\":\"AD\",\"id\":\"a1\"}],"
            + "\"attributes\":{\"dept\":\"IT\",\"region\":null}}";
    private static final String NEW_JSON = "{\"source_id\":\"7f0001019714176881971476c14808ed\","
            + "\"name\":\"Bob.Admin\",\"display_name\":\"Bob\",\"inactive\":false,\"correlated\":false}";

    @Test
    void migratesLegacyTableSkipsSameHashUpdatesChangedDataAndRevivesDeletedIdentity() throws Exception {
        String url = System.getenv("KFAGENT_TEST_PG_URL");
        assumeTrue(url != null && !url.isBlank(), "set KFAGENT_TEST_PG_URL to run PostgreSQL behavior test");
        String username = System.getenv("KFAGENT_TEST_PG_USERNAME");
        String password = System.getenv("KFAGENT_TEST_PG_PASSWORD");
        String schema = "kf_identity_it_" + UUID.randomUUID().toString().replace("-", "");
        String userid = CanonicalIds.identityUserid("7f0001019714176881971476c14808ec", "Alice.Admin");
        Map<String, String> previous = new LinkedHashMap<>();
        for (String key : new String[]{"KFAGENT_BASE_URL", "KFAGENT_USERNAME", "KFAGENT_PASSWORD", "PG_HOST",
                "PG_DATABASE", "PG_USERNAME", "PG_PASSWORD", "PG_DEST_SCHEMA", "KFAGENT_PAGE_SIZE"})
            previous.put(key, System.getProperty(key));

        HttpServer server = null;
        try {
            try (Connection conn = connection(url, username, password); Statement st = conn.createStatement()) {
                st.execute("CREATE SCHEMA " + schema);
                String legacyDdl = new IdentityRepository(schema).createTableSql().replace(",\n    record_hash text", "");
                st.execute(legacyDdl);
                st.execute("INSERT INTO " + schema + ".kf_identity(userid, display_name, extraction_run_id) VALUES ('"
                        + userid + "'::uuid, 'Alice', 'legacy-run')");
            }

            AtomicReference<String> body = new AtomicReference<>("[" + BASE_JSON + "," + NEW_JSON + "]");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/kfagent/identities", exchange -> {
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();

            System.setProperty("KFAGENT_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            System.setProperty("KFAGENT_USERNAME", "agent-user");
            System.setProperty("KFAGENT_PASSWORD", "agent-pass");
            System.setProperty("PG_HOST", "unused");
            System.setProperty("PG_DATABASE", "unused");
            System.setProperty("PG_USERNAME", "unused");
            System.setProperty("PG_PASSWORD", "unused");
            System.setProperty("PG_DEST_SCHEMA", schema);
            System.setProperty("KFAGENT_PAGE_SIZE", "10");

            IdentityImportService service = new IdentityImportService(new KfAgentClient(AppConfig.load()),
                    new IdentityRepository(schema), 10);
            try (Connection conn = connection(url, username, password)) {
                ImportResult first = service.run(conn, "first-run");
                assertEquals(1, first.inserted());
                assertEquals(1, first.updated(), "legacy null hash is initialized on first encounter");
                assertEquals(0, first.skipped());

                Timestamp firstExtractedAt = extractedAt(conn, schema, userid);
                ImportResult same = service.run(conn, "same-run");
                assertEquals(2, same.skipped());
                assertEquals(0, same.updated());
                assertEquals(firstExtractedAt, extractedAt(conn, schema, userid));
                assertEquals("first-run", extractionRunId(conn, schema, userid),
                        "a skipped row retains the original extraction metadata");

                body.set("[" + BASE_JSON.replace("display_name\":\"Alice\"", "display_name\":\"Alice Changed\"")
                        + "," + NEW_JSON + "]");
                ImportResult changed = service.run(conn, "changed-run");
                assertEquals(1, changed.updated());
                assertEquals(1, changed.skipped());

                try (Statement st = conn.createStatement()) {
                    st.execute("UPDATE " + schema + ".kf_identity SET is_deleted=true, deleted_at=now() WHERE userid='"
                            + userid + "'::uuid");
                }
                ImportResult revived = service.run(conn, "revived-run");
                assertEquals(1, revived.updated());
                assertEquals(1, revived.skipped());
                assertFalse(isDeleted(conn, schema, userid));
                assertEquals("revived-run", extractionRunId(conn, schema, userid));
            }
        } finally {
            if (server != null) server.stop(0);
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            });
            try (Connection conn = connection(url, username, password); Statement st = conn.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static Connection connection(String url, String username, String password) throws Exception {
        return username == null ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, username, password == null ? "" : password);
    }

    private static Timestamp extractedAt(Connection conn, String schema, String userid) throws Exception {
        try (var ps = conn.prepareStatement("SELECT extracted_at FROM " + schema + ".kf_identity WHERE userid=?::uuid")) {
            ps.setString(1, userid);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getTimestamp(1); }
        }
    }

    private static String extractionRunId(Connection conn, String schema, String userid) throws Exception {
        try (var ps = conn.prepareStatement("SELECT extraction_run_id FROM " + schema + ".kf_identity WHERE userid=?::uuid")) {
            ps.setString(1, userid);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getString(1); }
        }
    }

    private static boolean isDeleted(Connection conn, String schema, String userid) throws Exception {
        try (var ps = conn.prepareStatement("SELECT is_deleted FROM " + schema + ".kf_identity WHERE userid=?::uuid")) {
            ps.setString(1, userid);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getBoolean(1); }
        }
    }
}

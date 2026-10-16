package com.keyforge.part2.http;

import com.keyforge.part2.config.AppConfig;
import com.keyforge.part2.config.ConfigException;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End to end against a local HTTPS server that, like an ngrok edge with mutual TLS, REQUIRES a client certificate:
 * the configured certificate is presented and accepted, Basic Auth still travels on top of it, and a certificate the
 * server does not trust is rejected. Uses the dummy, test-only keystores in {@code src/test/resources/mtls}.
 */
class KfAgentClientTlsTest {

    private static final String PASS = "changeit";
    private static final String[] KEYS = {"KFAGENT_BASE_URL", "KFAGENT_USERNAME", "KFAGENT_PASSWORD",
            "KFAGENT_CLIENT_KEYSTORE", "KFAGENT_CLIENT_KEYSTORE_PASSWORD", "PG_HOST", "PG_DATABASE", "PG_USERNAME",
            "PG_PASSWORD", "javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreType"};

    private final Map<String, String> saved = new HashMap<>();
    private final AtomicReference<String> seenClient = new AtomicReference<>();
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private HttpsServer server;

    @BeforeEach
    void setUp() throws Exception {
        for (String k : KEYS) {
            assumeTrue(System.getenv(k) == null || System.getenv(k).isBlank(), k + " is set in the environment");
            saved.put(k, System.getProperty(k));
            System.clearProperty(k);
        }
        // The mock agent's certificate is self-signed, so the CLIENT must trust it explicitly for this test. Production
        // keeps the JDK's standard CAs (ngrok's edge certificate is publicly trusted).
        System.setProperty("javax.net.ssl.trustStore", resource("client-trust.p12"));
        System.setProperty("javax.net.ssl.trustStorePassword", PASS);
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("localhost"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext()) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true); // no trusted client certificate -> no connection, as at the ngrok edge
                params.setSSLParameters(ssl);
            }
        });
        server.createContext("/kfagent/identities", ex -> {
            seenClient.set(((HttpsExchange) ex).getSSLSession().getPeerPrincipal().getName());
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add(KfAgentClient.VERIFIED_SOURCE_COUNT_HEADER, "0");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        System.setProperty("KFAGENT_BASE_URL", "https://localhost:" + server.getAddress().getPort());
        System.setProperty("KFAGENT_USERNAME", "client-id");
        System.setProperty("KFAGENT_PASSWORD", "client-secret");
        System.setProperty("PG_HOST", "localhost");
        System.setProperty("PG_DATABASE", "db");
        System.setProperty("PG_USERNAME", "u");
        System.setProperty("PG_PASSWORD", "p");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        saved.forEach((k, v) -> {
            if (v == null) System.clearProperty(k); else System.setProperty(k, v);
        });
    }

    private static String resource(String name) throws Exception {
        return Path.of(KfAgentClientTlsTest.class.getResource("/mtls/" + name).toURI()).toString();
    }

    private static KeyStore load(String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(resource(name)))) {
            ks.load(in, PASS.toCharArray());
        }
        return ks;
    }

    private static SSLContext serverContext() throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(load("server.p12"), PASS.toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(load("server-trust.p12")); // accepts only the test client's certificate
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ssl;
    }

    private static KfAgentClient clientWith(String keystore) throws Exception {
        System.setProperty("KFAGENT_CLIENT_KEYSTORE", resource(keystore));
        System.setProperty("KFAGENT_CLIENT_KEYSTORE_PASSWORD", PASS);
        return new KfAgentClient(AppConfig.load());
    }

    @Test
    void trustedClientCertificateIsPresentedAndBasicAuthStillSent() throws Exception {
        KfAgentClient.Snapshot s = clientWith("client.p12").fetchSnapshot("identities");
        assertEquals("[]", s.body());
        assertEquals(0, s.verifiedSourceCount());
        assertTrue(seenClient.get().contains("CN=kfagent-ingest test client"), seenClient.get());
        assertEquals("Basic Y2xpZW50LWlkOmNsaWVudC1zZWNyZXQ=", seenAuth.get(), "client-id:client-secret, unchanged");
    }

    @Test
    void certificateTheServerDoesNotTrustIsRejected() throws Exception {
        KfAgentClient client = clientWith("stranger.p12");
        assertThrows(KfAgentClient.KfAgentHttpException.class, () -> client.fetchSnapshot("identities"));
        assertEquals(null, seenClient.get(), "the request never reached the handler");
    }

    @Test
    void wrongKeystorePasswordIsAClearConfigErrorWithoutThePassword() throws Exception {
        ConfigException e = assertThrows(ConfigException.class,
                () -> KfAgentClient.clientCertificateContext(resource("client.p12"), "wrong-pass".toCharArray()));
        assertTrue(e.getMessage().contains("KFAGENT_CLIENT_KEYSTORE_PASSWORD"), e.getMessage());
        assertFalse(e.getMessage().contains("wrong-pass"), "no password in the error");
    }

    @Test
    void fileThatIsNotAKeystoreIsAClearConfigError() throws Exception {
        ConfigException e = assertThrows(ConfigException.class,
                () -> KfAgentClient.clientCertificateContext(resource("README.txt"), PASS.toCharArray()));
        assertTrue(e.getMessage().contains("PKCS12"), e.getMessage());
    }
}

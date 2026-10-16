TEST-ONLY dummy keystores (self-signed, password "changeit"), generated with keytool for KfAgentClientTlsTest.
They protect nothing and are NOT the customer certificate. Never use them outside the tests.
  server.p12        - the mock agent's TLS identity (CN=localhost)
  server-trust.p12  - what the mock agent accepts as a client: only client.p12's certificate
  client.p12        - the trusted test client certificate
  stranger.p12      - a client certificate the mock agent does NOT trust
  client-trust.p12  - what the test client trusts as a server: server.p12's certificate

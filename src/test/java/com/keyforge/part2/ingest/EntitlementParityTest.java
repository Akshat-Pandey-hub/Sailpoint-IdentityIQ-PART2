package com.keyforge.part2.ingest;

import com.keyforge.part2.testsupport.ExpectedKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyforge.part2.hash.RecordHash;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Parity with Part 1. {@code entitlement-part1-golden.txt} was produced by running Part 1's OWN code
 * (NativeManagedAttributeParser → NativeManagedAttributeRepository.recordHash / canonicalEntitlementId, and
 * NativeEntitlementRestService.toJson for the REST body) — so each line is: the exact JSON the KF Agent
 * serves, and the record_hash + entitlementid Part 1 persisted for that same object. Part 2 must reproduce
 * both from the REST JSON alone. Covers nested JSON, unicode, decimals, nanosecond/second-precision
 * timestamps, nulls, and the no-source_id fallback key.
 */
class EntitlementParityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private record Golden(JsonNode rest, String hash, String id) { }

    private List<Golden> golden() throws Exception {
        List<Golden> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/entitlement-part1-golden.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String rest = null, hash = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("REST=")) rest = line.substring(5);
                else if (line.startsWith("HASH=")) hash = line.substring(5);
                else if (line.startsWith("ID=")) out.add(new Golden(mapper.readTree(rest), hash, line.substring(3)));
            }
        }
        assertEquals(2, out.size(), "golden fixture rows");
        return out;
    }

    @Test
    void recordHashMatchesPart1ForTheSameObject() throws Exception {
        for (Golden g : golden()) {
            assertEquals(g.hash(), EntitlementTable.recordHash(g.rest()), "record_hash parity for " + g.rest());
        }
    }

    @Test
    void entitlementIdMatchesPart1IncludingFallback() throws Exception {
        List<Golden> gs = golden();
        assertEquals("7f000101-9eb9-1d3c-819f-01f0c4127efb", gs.get(0).id(), "canonical form of source_id");
        for (Golden g : gs) {
            assertEquals(ExpectedKey.of(g.id(), g.rest(), "native-managedattribute"), EntitlementTable.entitlementId(g.rest()));
        }
    }

    @Test
    void hashIgnoresColumnsPart1LeftOutOfTheHash() throws Exception {
        Golden g = golden().get(0);
        com.fasterxml.jackson.databind.node.ObjectNode changed = g.rest().deepCopy();
        changed.put("source_hash", "different");
        changed.put("full_name", "different");
        changed.put("is_inactive", true);
        changed.putArray("classification_names").add("X");
        assertEquals(g.hash(), EntitlementTable.recordHash(changed), "excluded columns must not affect record_hash");

        changed.put("value", "other-value");
        assertNotEquals(g.hash(), EntitlementTable.recordHash(changed), "a hashed business field changes it");
    }

    @Test
    void recordHashAlgorithmIsSortedKeyValueSha256WithSpaceForNull() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", null);
        m.put("a", Boolean.TRUE);
        // sha256("a=true\nb= \n")
        assertEquals("23aaba9a6ae8fe77c4bd045c01effe05508a805c1a1d5f3ff8ed2d33e141ca17", RecordHash.of(m));
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("a", Boolean.TRUE);
        reordered.put("b", null);
        assertEquals(RecordHash.of(m), RecordHash.of(reordered), "key order must not matter");
    }
}

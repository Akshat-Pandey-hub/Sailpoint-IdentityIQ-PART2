package com.keyforge.part2.id;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins Part 2's id rules to Part 1's ParquetIds behavior (the whole point: identical userid values). */
class CanonicalIdsTest {

    @Test
    void thirtyTwoHexBecomesDashedUuidWithSameValue() {
        String raw = "7f0001019714176881971476c14808ec";
        String uuid = CanonicalIds.canonicalUuid(raw);
        assertEquals("7f000101-9714-1768-8197-1476c14808ec", uuid);
        assertEquals(uuid, UUID.fromString(uuid).toString(), "is a valid UUID");
        // same 32 hex chars, dashes removed, lower-cased
        assertEquals(raw, uuid.replace("-", ""));
    }

    @Test
    void acceptsDashedAndBracedAndRejectsInvalid() {
        assertEquals("7f000101-9714-1768-8197-1476c14808ec",
                CanonicalIds.canonicalUuid("7f000101-9714-1768-8197-1476c14808ec"));
        assertEquals("7f000101-9714-1768-8197-1476c14808ec",
                CanonicalIds.canonicalUuid("{7f0001019714176881971476c14808ec}"));
        assertNull(CanonicalIds.canonicalUuid(null));
        assertNull(CanonicalIds.canonicalUuid("  "));
        assertNull(CanonicalIds.canonicalUuid("not-a-guid"));
        assertNull(CanonicalIds.canonicalUuid("1234")); // wrong length
    }

    @Test
    void deterministicUuidIsStableAndNameBased() {
        String a = CanonicalIds.deterministicUuid("native-identity|alice");
        String b = CanonicalIds.deterministicUuid("native-identity|alice");
        assertEquals(a, b, "same seed -> same uuid");
        assertEquals(UUID.nameUUIDFromBytes("native-identity|alice".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), a);
        assertTrue(!a.equals(CanonicalIds.deterministicUuid("native-identity|bob")), "different seed -> different uuid");
    }

    @Test
    void identityUseridUsesCanonicalWhenSourceIdPresent() {
        String raw = "7f0001019714176881971476c14808ec";
        assertEquals("7f000101-9714-1768-8197-1476c14808ec", CanonicalIds.identityUserid(raw, "Alice"));
    }

    @Test
    void identityUseridFallsBackDeterministicallyWhenSourceIdBlankOrInvalid() {
        // blank source_id -> deterministicUuid("native-identity|" + name)
        String byName = CanonicalIds.identityUserid("", "Alice");
        assertEquals(CanonicalIds.deterministicUuid("native-identity|Alice"), byName);
        assertNotNull(byName);
        // invalid (non-GUID) source_id -> deterministicUuid("native-identity|" + source_id)
        String bad = CanonicalIds.identityUserid("weird-id", "Alice");
        assertEquals(CanonicalIds.deterministicUuid("native-identity|weird-id"), bad);
        // stable across calls
        assertEquals(bad, CanonicalIds.identityUserid("weird-id", "Alice"));
    }
}

package com.keyforge.part2.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Deterministic fingerprint and event id for {@code kf_event}: Part 1's {@code iiq.event.EventFingerprint}, unchanged.
 * {@code event_fingerprint} = md5 (lower-case hex) of the order-preserving {@code |}-join of the event's business
 * attributes (null → empty, trimmed, NOT lower-cased). {@code event_id} = {@code UUID.nameUUIDFromBytes(
 * src_system|src_object_type|src_object_id|event_fingerprint)}: the same source fact always maps to the same id, and a
 * changed business attribute yields a different fingerprint, hence a new event (never a mutation).
 */
public final class EventFingerprint {

    /** Logical source system. */
    public static final String SRC_SYSTEM = "IdentityIQ";

    private EventFingerprint() {
    }

    public static String fingerprint(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(norm(parts[i]));
        }
        return md5(sb.toString());
    }

    public static String eventId(String srcSystem, String srcObjectType, String srcObjectId, String fingerprint) {
        String key = norm(srcSystem) + "|" + norm(srcObjectType) + "|" + norm(srcObjectId) + "|" + norm(fingerprint);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    static String md5(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}

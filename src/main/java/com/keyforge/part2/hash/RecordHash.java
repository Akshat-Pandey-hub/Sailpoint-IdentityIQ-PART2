package com.keyforge.part2.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * EXACT reproduction of Part 1's {@code com.keyforge.nativeload.NativeRecordHash}: SHA-256 (lowercase hex)
 * over {@code key=value\n} lines, keys sorted, a {@code null} value rendered as a single space. Values are
 * rendered with {@code String.valueOf}, so callers must pass the same Java types Part 1 hashed (String,
 * Boolean, {@link java.time.Instant}, compact-JSON String) — see each entity's hash function.
 * Reproduced (not depended on) to keep Part 2 independent of Part 1; {@code RecordHashTest} pins it.
 */
public final class RecordHash {

    private RecordHash() {
    }

    public static String of(Map<String, Object> businessFields) {
        TreeMap<String, Object> sorted = new TreeMap<>(businessFields);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? " " : e.getValue()).append('\n');
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

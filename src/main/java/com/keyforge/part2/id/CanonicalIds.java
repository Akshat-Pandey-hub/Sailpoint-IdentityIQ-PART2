package com.keyforge.part2.id;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * EXACT reproduction of Part 1's {@code com.keyforge.iiq.parquet.ParquetIds} id rules, so Part 2 produces
 * the identical primary-key UUIDs the native extraction does (same {@code userid} for the same identity →
 * idempotent upserts and join-compatibility with Part 1 data). Reproduced (not depended on) to avoid
 * pulling in the whole Part 1 project; {@code CanonicalIdsTest} pins the behavior.
 *
 * <p>{@code userid} is NOT a new/random id: {@link #canonicalUuid} rewrites the raw 32-hex IIQ id into its
 * dashed UUID form (same value, canonical shape). {@link #deterministicUuid} is only the fallback for
 * records whose source gives no usable id.
 */
public final class CanonicalIds {

    private CanonicalIds() {
    }

    /** 32-hex or dashed/braced GUID → canonical dashed UUID; {@code null} if blank/invalid. */
    public static String canonicalUuid(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String s = rawId.trim();
        if (s.startsWith("{") && s.endsWith("}") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        String hex = s.replace("-", "");
        if (hex.length() == 32 && hex.matches("[0-9a-fA-F]{32}")) {
            String dashed = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                    + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
            try {
                return UUID.fromString(dashed).toString();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        try {
            return UUID.fromString(s).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Deterministic name-based UUID over the seed (for records the source gives no id). */
    public static String deterministicUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * The Identity primary key, matching Part 1's {@code NativeIdentityRepository.canonicalUserid}:
     * {@code canonicalUuid(source_id)}, falling back to {@code deterministicUuid("native-identity|"+id)}
     * (id = source_id, or name when source_id is blank).
     */
    public static String identityUserid(String sourceId, String name) {
        String userid = canonicalUuid(sourceId);
        if (userid == null) {
            String seedKey = (sourceId == null || sourceId.isBlank()) ? name : sourceId;
            userid = deterministicUuid("native-identity|" + seedKey);
        }
        return userid;
    }
}

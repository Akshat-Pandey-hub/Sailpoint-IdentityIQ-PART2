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

    /**
     * The Entitlement primary key, matching Part 1's
     * {@code NativeManagedAttributeRepository.canonicalEntitlementId}: {@code canonicalUuid(source_id)},
     * falling back to {@code deterministicUuid("native-managedattribute|"+id)}. Note Part 1 falls back to
     * {@code name} only when source_id is {@code null} (not merely blank) — reproduced as-is.
     */
    public static String entitlementId(String sourceId, String name) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-managedattribute|" + (sourceId == null ? name : sourceId));
        }
        return id;
    }

    /**
     * The Application primary key, matching Part 1's {@code NativeApplicationRepository.canonicalApplicationId}:
     * {@code canonicalUuid(source_id)}, falling back to {@code deterministicUuid("native-application|"+id)}
     * ({@code name} only when source_id is {@code null}).
     */
    public static String applicationId(String sourceId, String name) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-application|" + (sourceId == null ? name : sourceId));
        }
        return id;
    }

    /**
     * The Account (Link) primary key, matching Part 1's {@code NativeLinkRepository.canonicalAccountId}:
     * {@code canonicalUuid(source_id)}, falling back to
     * {@code deterministicUuid("native-link|" + application_name + "|" + native_identity)} when source_id is
     * {@code null} (Java string concatenation, so a null part becomes the text "null" — reproduced as-is), or
     * {@code "native-link|" + source_id} when source_id is present but not a GUID.
     */
    public static String accountId(String sourceId, String applicationName, String nativeIdentity) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-link|"
                    + (sourceId == null ? (applicationName + "|" + nativeIdentity) : sourceId));
        }
        return id;
    }

    /**
     * The Workgroup primary key, matching Part 1's {@code NativeWorkgroupRepository.canonicalWorkgroupId}:
     * {@code canonicalUuid(source_id)}, falling back to {@code deterministicUuid("native-workgroup|"+id)}
     * ({@code name} only when source_id is {@code null}).
     */
    public static String workgroupId(String sourceId, String name) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-workgroup|" + (sourceId == null ? name : sourceId));
        }
        return id;
    }

    /**
     * The Role (Bundle) primary key, matching Part 1's {@code NativeRoleRepository.canonicalRoleId}:
     * {@code canonicalUuid(source_id)}, falling back to {@code deterministicUuid("native-role|"+id)}
     * ({@code name} only when source_id is {@code null}).
     */
    public static String roleId(String sourceId, String name) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-role|" + (sourceId == null ? name : sourceId));
        }
        return id;
    }

    /**
     * The Identity-Role edge primary key, matching Part 1's {@code NativeIdentityRoleRepository.canonicalEdgeId}.
     * An edge has no IIQ object id of its own, so the key is ALWAYS name-based (no canonicalUuid step) over
     * {@code identity_id|role_id|relationship_type|assignment_id}; a null part is concatenated as the text
     * "null", exactly like Part 1.
     */
    public static String identityRoleId(String identityId, String roleId, String relationshipType,
                                        String assignmentId) {
        return deterministicUuid("native-identity-role|" + identityId + "|" + roleId
                + "|" + relationshipType + "|" + assignmentId);
    }

    /**
     * The Identity-Entitlement primary key, matching Part 1's
     * {@code NativeIdentityEntitlementRepository.canonicalEntitlementId}: {@code canonicalUuid(source_id)} (the
     * IIQ IdentityEntitlement id); when that is null/blank/not a GUID, the composite natural key
     * {@code identity_id|application_name|native_identity|attribute_name|attribute_value} (source_id itself is
     * NOT part of the fallback; a null part is concatenated as the text "null", exactly like Part 1).
     */
    public static String identityEntitlementId(String sourceId, String identityId, String applicationName,
                                               String nativeIdentity, String attributeName, String attributeValue) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-identity-entitlement|" + identityId + "|" + applicationName + "|"
                    + nativeIdentity + "|" + attributeName + "|" + attributeValue);
        }
        return id;
    }
}

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

    /**
     * The Account-Entitlement edge primary key, matching Part 1's
     * {@code NativeAccountEntitlementRepository.canonicalEdgeId}. Always name-based (an edge has no IIQ id), and the
     * formula depends on the edge type ({@code null} type counts as ATTRIBUTE):
     * <ul>
     *   <li>ATTRIBUTE: {@code "native-account-entitlement|" + link_id|attribute_name|attribute_value}</li>
     *   <li>otherwise (PERMISSION / TARGET_PERMISSION):
     *       {@code "native-account-entitlement|" + link_id|type|permission_target|permission_rights|permission_annotation}</li>
     * </ul>
     * A null part is concatenated as the text "null", exactly like Part 1.
     */
    public static String accountEntitlementId(String linkId, String type, String attributeName, String attributeValue,
                                              String permissionTarget, String permissionRights,
                                              String permissionAnnotation) {
        String t = type == null ? "ATTRIBUTE" : type;
        if ("ATTRIBUTE".equals(t)) {
            return deterministicUuid("native-account-entitlement|" + linkId + "|" + attributeName + "|" + attributeValue);
        }
        return deterministicUuid("native-account-entitlement|" + linkId + "|" + t
                + "|" + permissionTarget + "|" + permissionRights + "|" + permissionAnnotation);
    }

    /**
     * The IdentityRequest primary key, matching Part 1's {@code NativeIdentityRequestRepository.canonicalRequestId}:
     * {@code canonicalUuid(source_id)} with NO fallback — Part 1 refuses a request without a usable IIQ id
     * ("source id is required for deterministic identity"), so the row fails instead of getting an invented key.
     */
    public static String identityRequestId(String sourceId) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            throw new IllegalArgumentException("IdentityRequest source id is required for deterministic identity (got "
                    + (sourceId == null ? "null" : "'" + sourceId + "'") + ")");
        }
        return id;
    }

    /**
     * The IdentityRequestItem primary key, matching Part 1's {@code NativeIdentityRequestItemRepository.canonicalItemId}:
     * the ITEM's own IIQ id ({@code canonicalUuid(source_id)}), else {@code deterministicUuid("native-identity-request-item|"
     * + source_id)} (a null id becomes the text "null", like Part 1). The parent request id is NOT part of the key.
     */
    public static String identityRequestItemId(String sourceId) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-identity-request-item|" + sourceId);
        }
        return id;
    }

    /**
     * The IdentityRequest approval primary key, matching Part 1's
     * {@code NativeIdentityRequestApprovalRepository.canonicalApprovalId}. An approval summary has no IIQ id of its own,
     * so the key is ALWAYS name-based over {@code request_source_id|work_item_id|owner|approval_index} (a null part,
     * including a null index, becomes the text "null", exactly like Part 1).
     */
    public static String identityRequestApprovalId(String requestSourceId, String workItemId, String owner,
                                                   Integer approvalIndex) {
        return deterministicUuid("native-identity-request-approval|" + requestSourceId + "|" + workItemId + "|"
                + owner + "|" + approvalIndex);
    }

    /**
     * The Policy primary key, matching Part 1's {@code NativePolicyRepository.canonicalPolicyId}:
     * {@code canonicalUuid(source_id)}, falling back to {@code deterministicUuid("native-policy|"+id)}
     * ({@code name} only when source_id is {@code null}).
     */
    public static String policyId(String sourceId, String name) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-policy|" + (sourceId == null ? name : sourceId));
        }
        return id;
    }

    /**
     * The ProvisioningTransaction primary key, matching Part 1's {@code NativeProvisioningTxnRepository.canonicalTxnId}:
     * {@code canonicalUuid(source_id)}; a present but non-GUID id falls back to
     * {@code deterministicUuid("native-provisioning-txn|"+id)}; a null/blank id is REFUSED (the row fails), as in Part 1.
     */
    public static String provisioningTxnId(String sourceId) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            if (sourceId == null || sourceId.isBlank()) {
                throw new IllegalArgumentException("ProvisioningTransaction source id is required for deterministic identity");
            }
            id = deterministicUuid("native-provisioning-txn|" + sourceId);
        }
        return id;
    }

    /**
     * The WorkItem primary key, matching Part 1's {@code NativeWorkItemRepository.canonicalWorkItemId}:
     * {@code canonicalUuid(source_id)} with NO fallback — a work item without a usable IIQ id is refused (row fails).
     */
    public static String workItemId(String sourceId) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            throw new IllegalArgumentException("WorkItem source id is required for deterministic identity (got "
                    + (sourceId == null ? "null" : "'" + sourceId + "'") + ")");
        }
        return id;
    }

    /**
     * The Certification primary key, matching Part 1's {@code NativeCertificationRepository.canonicalCertificationId}:
     * {@code canonicalUuid(source_id)}, falling back to {@code deterministicUuid("native-certification|"+id)}
     * ({@code name} only when source_id is {@code null}).
     */
    public static String certificationId(String sourceId, String name) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-certification|" + (sourceId == null ? name : sourceId));
        }
        return id;
    }

    /**
     * The CertificationEntity primary key, matching Part 1's
     * {@code NativeCertificationEntityRepository.canonicalCertificationEntityId}: {@code canonicalUuid(source_id)},
     * falling back to {@code deterministicUuid("native-certification-entity|"+id)} — with the entity's {@code identity}
     * (not a name) only when source_id is {@code null}.
     */
    public static String certificationEntityId(String sourceId, String identity) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-certification-entity|" + (sourceId == null ? identity : sourceId));
        }
        return id;
    }

    /**
     * The CertificationItem primary key, matching Part 1's {@code NativeCertificationItemRepository.canonicalItemId}:
     * {@code canonicalUuid(source_id)}, falling back to {@code deterministicUuid("native-certification-item|"+source_id)}
     * — no name/identity fallback (a null id becomes the text "null", exactly like Part 1).
     */
    public static String certificationItemId(String sourceId) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-certification-item|" + sourceId);
        }
        return id;
    }

    /**
     * The policy-constraint primary key, matching Part 1's {@code NativePolicyConstraintRepository.canonicalConstraintId}:
     * {@code canonicalUuid(source_id)}, else the composite {@code deterministicUuid("native-policy-constraint|" + policy_id
     * + "|" + (source_id, or name when source_id is null) + "|" + constraint_type)} (null parts become the text "null").
     */
    public static String policyConstraintId(String sourceId, String policyId, String name, String constraintType) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-policy-constraint|" + policyId + "|"
                    + (sourceId != null ? sourceId : name) + "|" + constraintType);
        }
        return id;
    }

    /**
     * The PolicyViolation primary key, matching Part 1's {@code NativeViolationRepository.canonicalViolationId}:
     * {@code canonicalUuid(source_id)}, falling back to {@code deterministicUuid("native-violation|"+id)}
     * ({@code name} only when source_id is {@code null}).
     */
    public static String violationId(String sourceId, String name) {
        String id = canonicalUuid(sourceId);
        if (id == null) {
            id = deterministicUuid("native-violation|" + (sourceId == null ? name : sourceId));
        }
        return id;
    }
}

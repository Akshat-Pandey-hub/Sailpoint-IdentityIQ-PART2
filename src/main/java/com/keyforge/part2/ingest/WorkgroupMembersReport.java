package com.keyforge.part2.ingest;

import java.util.List;

/**
 * {@code GET /kfagent/workgroup-members} → {@code kf_workgroup_members}: Part 1's SQL-report "Query 2" (registered
 * report id {@code workgroupMembers}), the workgroup membership roster with member attributes and status. Stored as the
 * query result, exactly as Part 1's {@code WORKGROUP_MEMBERS_COLUMNS}: 7 text columns in SELECT-alias order,
 * replace-all per run (same {@link SqlReportRepository} contract as {@link EntitlementAssignmentReport}).
 *
 * <p>NOT the native edge entity {@code /kfagent/workgroupmember} → {@code kf_workgroup_member}
 * ({@link WorkgroupMemberRepository}); both exist in Part 1 and stay separate.
 *
 * <p>Relationships are values inside each row, not keys: {@code workgroup_name} is the workgroup identity's name and
 * {@code user_name} the member identity's name. Every workgroup appears (LEFT JOINs): one row per member, or one row
 * with NULL member columns and {@code user_status} 'No Members'; otherwise 'Disabled' when the member is inactive, else
 * 'Active'. No entitlement or certification relationship. The row's {@code id} is a database row number only.
 */
public final class WorkgroupMembersReport {

    private WorkgroupMembersReport() {
    }

    public static final String RESOURCE = "workgroup-members";
    public static final String TABLE = "kf_workgroup_members";

    /** Part 1 {@code Main.WORKGROUP_MEMBERS_COLUMNS}: the query's SELECT aliases, in order. */
    public static final List<String> COLUMNS = List.of(
            "workgroup_name", "user_name", "display_name", "first_name", "last_name", "email", "user_status");
}

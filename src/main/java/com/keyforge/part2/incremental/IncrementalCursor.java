package com.keyforge.part2.incremental;

/**
 * Which IIQ timestamp an incremental import follows, and the destination column that stores it.
 * <ul>
 *   <li>{@link #MODIFIED}: current-state objects (identity, account, application, entitlement, role); the KF Agent's
 *       {@code modifiedAfter} bounds IIQ {@code modified}, and the watermark is {@code max(modified_at)}.</li>
 *   <li>{@link #CREATED}: event-shaped, append-only objects (audit events, syslog events, work-item and certification
 *       archives, Access History records), which IIQ writes once and never modifies; the KF Agent's
 *       {@code modifiedAfter} bounds IIQ {@code created}, and the watermark is {@code max(created_at)}.</li>
 *   <li>{@link #CHANGED}: current-state objects whose IIQ {@code modified} stays NULL until first updated (identity
 *       requests, provisioning transactions, work items, task results, certification items and entities); the KF
 *       Agent's {@code modifiedAfter} bounds "modified after it, or never modified and created after it", and the
 *       watermark is {@code max(coalesce(modified_at, created_at))} so never-modified rows count too.</li>
 * </ul>
 * Column names are fixed constants (never caller input), so they are safe to place in SQL.
 */
public enum IncrementalCursor {

    MODIFIED("modified", "modified_at"),
    CREATED("created", "created_at"),
    CHANGED("modified", "coalesce(modified_at, created_at)"),
    /** Rows Part 2 wrote (its own {@code extracted_at}): the watermark of the derived event / link commands. */
    EXTRACTED("extracted", "extracted_at");

    private final String sourceField;
    private final String column;

    IncrementalCursor(String sourceField, String column) {
        this.sourceField = sourceField;
        this.column = column;
    }

    /** The IIQ property the bound applies to (recorded as the watermark's field). */
    public String sourceField() {
        return sourceField;
    }

    /** The destination column (or fixed column expression) holding that IIQ time; its max is the new watermark. */
    public String column() {
        return column;
    }
}

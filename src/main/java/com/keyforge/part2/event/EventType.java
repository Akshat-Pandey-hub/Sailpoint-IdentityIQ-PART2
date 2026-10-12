package com.keyforge.part2.event;

/**
 * The event kinds the native derivation emits (Part 1 {@code iiq.event.EventType} plus the native
 * {@code CERTIFICATION_DECISION}). No IIQ action taxonomy is invented.
 */
public final class EventType {

    /** IdentityRequest approval that has been decided (end_date present). */
    public static final String APPROVAL_DECISION = "APPROVAL_DECISION";
    /** IdentityRequest approval still open (no end_date). */
    public static final String APPROVAL_OPEN = "APPROVAL_OPEN";
    /** TaskResult that has completed. */
    public static final String TASK_COMPLETED = "TASK_COMPLETED";
    /** TaskResult that has launched but not completed. */
    public static final String TASK_LAUNCHED = "TASK_LAUNCHED";
    /** A provisioning transaction. */
    public static final String PROVISIONING_TXN = "PROVISIONING_TXN";
    /** A work item that has been archived (one event per WorkItemArchive). */
    public static final String WORKITEM_ARCHIVED = "WORKITEM_ARCHIVED";
    /** A certification item decision (emitted only when a decision exists). */
    public static final String CERTIFICATION_DECISION = "CERTIFICATION_DECISION";

    private EventType() {
    }
}

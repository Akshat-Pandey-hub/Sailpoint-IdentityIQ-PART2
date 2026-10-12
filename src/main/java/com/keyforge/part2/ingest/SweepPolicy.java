package com.keyforge.part2.ingest;

/**
 * Whether, and how, a table's soft-delete sweep may run (see {@link SoftDeleteSweeper}). Mirrors the guard groups found
 * in Part 1's import services; every table is {@link #NEVER} until explicitly enabled (see {@link SweepPolicies}).
 */
public enum SweepPolicy {

    /** Never sweep. The default for every table, and the only allowed policy for append-only / report / governance tables. */
    NEVER,

    /**
     * Sweep after a verified-complete scan; keys of rows whose write failed stay in the keep-set, so a failed row is never
     * marked deleted (Part 1's {@code SoftDeleteSweeper} users: the key is added to the keep-set before the upsert).
     */
    SWEEP_PROTECT_FAILED_KEYS,

    /**
     * Sweep after a verified-complete scan only if NO row failed; any failure skips the whole sweep (Part 1's
     * role-relationship, workflow-definition and workgroup-membership imports).
     */
    SWEEP_UNLESS_ANY_FAILURE
}

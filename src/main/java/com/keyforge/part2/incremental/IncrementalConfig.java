package com.keyforge.part2.incremental;

import com.keyforge.part2.config.ConfigException;

import java.time.Duration;

/**
 * The incremental overlap window (Part 1 {@code iiq.incremental.IncrementalConfig}, same key and default): an
 * incremental run asks for objects modified after {@code watermark - overlap}, absorbing source clock skew and late
 * commits; the idempotent upserts absorb the re-read rows. Environment variable first, then JVM system property.
 */
public final class IncrementalConfig {

    /** Env var / system property naming the overlap window in whole minutes ({@code >= 0}). */
    public static final String KEY_OVERLAP_MINUTES = "INCREMENTAL_OVERLAP_MINUTES";

    /** Project default (Part 1's; not specified by the PDF). */
    public static final long DEFAULT_OVERLAP_MINUTES = 5;

    private IncrementalConfig() {
    }

    public static Duration overlapWindow() {
        return parseOverlapMinutes(resolve(KEY_OVERLAP_MINUTES));
    }

    /** Pure parse: blank → default; otherwise a whole number of minutes {@code >= 0}. */
    public static Duration parseOverlapMinutes(String raw) {
        if (raw == null || raw.isBlank()) {
            return Duration.ofMinutes(DEFAULT_OVERLAP_MINUTES);
        }
        String s = raw.trim();
        long minutes;
        try {
            minutes = Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new ConfigException(KEY_OVERLAP_MINUTES
                    + " must be a whole number of minutes (>= 0) but was: '" + s + "'");
        }
        if (minutes < 0) {
            throw new ConfigException(KEY_OVERLAP_MINUTES + " must be >= 0 but was: " + minutes);
        }
        return Duration.ofMinutes(minutes);
    }

    private static String resolve(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromSysProp = System.getProperty(key);
        return fromSysProp == null || fromSysProp.isBlank() ? null : fromSysProp.trim();
    }
}

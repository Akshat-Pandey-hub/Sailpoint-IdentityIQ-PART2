package com.keyforge.part2.incremental;

/**
 * Full or incremental import (Part 1 {@code iiq.incremental.ExtractionMode}). {@link #FULL} is the default when no flag
 * is given, so existing commands are unchanged; {@code --incremental} (alias {@code --delta}) selects
 * {@link #INCREMENTAL}, {@code --full} (alias {@code --initial}) forces full. Unlike Part 1's client-side filter, Part 2
 * passes the bound to the KF Agent as {@code modifiedAfter}, which the plugin applies server-side.
 */
public enum ExtractionMode {
    FULL,
    INCREMENTAL;

    /** The mode selected by the arguments after the command name; the last mode flag wins. */
    public static ExtractionMode fromArgs(String[] args) {
        ExtractionMode mode = FULL;
        if (args == null) {
            return mode;
        }
        for (String a : args) {
            if (a == null) {
                continue;
            }
            String flag = a.trim();
            if (flag.equalsIgnoreCase("--incremental") || flag.equalsIgnoreCase("--delta")) {
                mode = INCREMENTAL;
            } else if (flag.equalsIgnoreCase("--full") || flag.equalsIgnoreCase("--initial")) {
                mode = FULL;
            }
        }
        return mode;
    }

    public boolean isIncremental() {
        return this == INCREMENTAL;
    }
}

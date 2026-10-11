package com.keyforge.part2.config;

/** Thrown when required configuration is missing or invalid. */
public final class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}

package io.kiradb.services.config;

/**
 * One immutable snapshot in a config key's history.
 *
 * @param versionNumber  monotonic 1-based index, oldest = 1
 * @param timestampMillis epoch-millis when this version was written
 * @param value          the value at this version
 */
public record ConfigVersion(long versionNumber, long timestampMillis, String value) { }

package io.kiradb.client;

/**
 * One version in a config key's append-only history ({@code CFG.HIST} reply element).
 *
 * @param version         monotonically increasing version number (1 = first write)
 * @param timestampMillis epoch millis when this version was written
 * @param value           the value written at this version
 */
public record ConfigEntry(long version, long timestampMillis, String value) {
}

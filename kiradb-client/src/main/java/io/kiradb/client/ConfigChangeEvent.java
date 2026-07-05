package io.kiradb.client;

/**
 * A config change pushed by the server to a {@code CFG.WATCH} subscriber.
 *
 * <p>Decoded from the server's push frame
 * {@code ["CFG.NOTIFY", scope, key, value, version, timestampMillis]}.
 *
 * @param scope           the configuration scope the change happened in
 * @param key             the changed key
 * @param value           the new value
 * @param version         the new version number
 * @param timestampMillis epoch millis of the write on the server
 */
public record ConfigChangeEvent(
        String scope,
        String key,
        String value,
        long version,
        long timestampMillis) {
}

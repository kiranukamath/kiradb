package io.kiradb.services.config;

/**
 * A change event published by {@link ConfigStore} when a key's value changes.
 *
 * @param scope         configuration scope (e.g. "payment-service")
 * @param key           configuration key (e.g. "timeout")
 * @param newVersion    the version that just became current
 */
public record ConfigChange(String scope, String key, ConfigVersion newVersion) { }

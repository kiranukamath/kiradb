package io.kiradb.services.config;

/**
 * Notified when a config value changes. Implementations are responsible for
 * dispatching to interested subscribers (e.g. open Netty channels in
 * {@code ConfigSubscriptionRegistry}).
 */
@FunctionalInterface
public interface ConfigChangeListener {

    /**
     * Invoked synchronously by {@link ConfigStore} after a successful write.
     * Implementations must NOT block — heavy work belongs on a separate executor.
     *
     * @param change the change event
     */
    void onChange(ConfigChange change);
}

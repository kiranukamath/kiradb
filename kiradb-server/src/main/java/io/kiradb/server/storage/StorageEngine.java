package io.kiradb.server.storage;

/**
 * Server-layer alias for {@link io.kiradb.core.storage.StorageEngine}.
 *
 * <p>The canonical interface lives in {@code kiradb-core}. This extends it so that
 * existing command handlers in {@code kiradb-server} continue to compile without
 * changing their import statements.
 *
 * <p>All new code should import {@code io.kiradb.core.storage.StorageEngine} directly.
 */
public interface StorageEngine extends io.kiradb.core.storage.StorageEngine {
}

package io.kiradb.client;

/**
 * Cancellation handle for a config watch registered via
 * {@link ConfigClient#watch(String, java.util.function.Consumer)}.
 *
 * <p>Closing the handle removes the listener; when it was the last listener on
 * its scope the SDK also sends {@code CFG.UNWATCH} so the server stops pushing.
 * Closing twice is a no-op.
 */
public interface WatchHandle extends AutoCloseable {

    @Override
    void close();
}

package io.kiradb.client;

/**
 * Runtime exception for all SDK failures: server-side errors ({@code -ERR ...}
 * replies), connection failures, timeouts, and pool exhaustion.
 *
 * <p>Unchecked by design — a database client is used on virtually every code
 * path, and forcing {@code throws} clauses through application code buys
 * nothing (the standard position taken by JDBC's wrappers, Jedis, and Lettuce
 * alike). Callers that want to handle connectivity blips catch this at a
 * boundary of their choosing.
 */
public class KiraDBException extends RuntimeException {

    /**
     * Create an exception with a message.
     *
     * @param message what went wrong
     */
    public KiraDBException(final String message) {
        super(message);
    }

    /**
     * Create an exception wrapping a cause.
     *
     * @param message what went wrong
     * @param cause   the underlying failure
     */
    public KiraDBException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

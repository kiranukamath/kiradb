package io.kiradb.semanticcache.embedding;

/**
 * Thrown when an embedding provider fails to produce a vector — connection
 * refused, non-2xx HTTP status, or an unparseable response body.
 *
 * <p>Unchecked on purpose: an embedding failure is an infrastructure fault the
 * command handler translates into a RESP3 error, not something intermediate
 * layers can recover from.
 */
public class EmbeddingException extends RuntimeException {

    /**
     * Create an exception with a message only.
     *
     * @param message what failed
     */
    public EmbeddingException(final String message) {
        super(message);
    }

    /**
     * Create an exception wrapping a lower-level cause.
     *
     * @param message what failed
     * @param cause   the underlying I/O or parse failure
     */
    public EmbeddingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

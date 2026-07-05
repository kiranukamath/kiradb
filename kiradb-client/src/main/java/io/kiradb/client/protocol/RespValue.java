package io.kiradb.client.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Sealed hierarchy of RESP3 reply types as seen by the client.
 *
 * <p>This mirrors the server's {@code Resp3Value} but lives in the SDK with zero
 * dependencies — the client must be usable without the server on the classpath.
 * The client additionally understands the {@code >} push type ({@link Push}),
 * which servers use for out-of-band notifications (pub/sub style frames).
 *
 * <p>Wire format reference:
 * <pre>
 *   Simple   → +OK\r\n
 *   Error    → -ERR message\r\n
 *   Int      → :42\r\n
 *   Bulk     → $5\r\nhello\r\n
 *   Array    → *2\r\n ... elements ...
 *   Nil      → _\r\n            (RESP3 null; $-1 / *-1 also decode to Nil)
 *   Bool     → #t\r\n / #f\r\n  (RESP3)
 *   MapValue → %2\r\n k v k v   (RESP3)
 *   Push     → &gt;3\r\n ... elements ...  (RESP3 out-of-band)
 * </pre>
 */
public sealed interface RespValue permits
        RespValue.Simple,
        RespValue.Error,
        RespValue.Int,
        RespValue.Bulk,
        RespValue.Array,
        RespValue.Nil,
        RespValue.Bool,
        RespValue.MapValue,
        RespValue.Push {

    /**
     * A simple string reply, e.g. {@code +OK}.
     *
     * @param value the string payload
     */
    record Simple(String value) implements RespValue { }

    /**
     * An error reply, e.g. {@code -ERR unknown command}.
     *
     * @param message the full error message including the error code prefix
     */
    record Error(String message) implements RespValue { }

    /**
     * An integer reply, e.g. {@code :42}.
     *
     * @param value the integer payload
     */
    record Int(long value) implements RespValue { }

    /**
     * A bulk string reply — arbitrary bytes with an explicit length prefix.
     *
     * @param data the raw payload bytes
     */
    record Bulk(byte[] data) implements RespValue {

        /**
         * Decode the payload as UTF-8.
         *
         * @return the payload as a string
         */
        public String asString() {
            return new String(data, StandardCharsets.UTF_8);
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Bulk b && Arrays.equals(data, b.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "Bulk[" + asString() + "]";
        }
    }

    /**
     * An array reply — an ordered list of nested values.
     *
     * @param elements the nested values
     */
    record Array(List<RespValue> elements) implements RespValue { }

    /** The RESP3 null reply ({@code _\r\n}), also produced for RESP2 {@code $-1} / {@code *-1}. */
    record Nil() implements RespValue { }

    /**
     * A boolean reply ({@code #t} / {@code #f}).
     *
     * @param value the boolean payload
     */
    record Bool(boolean value) implements RespValue { }

    /**
     * A map reply ({@code %N}) — ordered key/value pairs.
     *
     * @param entries the map entries, in wire order
     */
    record MapValue(Map<RespValue, RespValue> entries) implements RespValue { }

    /**
     * An out-of-band push frame ({@code >N}) — same shape as an array but flagged
     * so clients can route it to a subscription dispatcher instead of treating it
     * as the reply to the in-flight command.
     *
     * @param elements the frame elements
     */
    record Push(List<RespValue> elements) implements RespValue { }
}

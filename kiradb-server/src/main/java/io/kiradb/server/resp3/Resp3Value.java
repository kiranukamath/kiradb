package io.kiradb.server.resp3;

import java.util.List;
import java.util.Map;

/**
 * Sealed hierarchy of all RESP3 wire types.
 *
 * <p>Using a sealed interface means the compiler knows every possible subtype.
 * Switch expressions on Resp3Value are exhaustive — no default needed, and the
 * compiler will tell you if you forget a case. This is what Java 25 records +
 * sealed classes are designed for.
 *
 * <p>Wire format reference:
 * <pre>
 *   SimpleString  → +OK\r\n
 *   SimpleError   → -ERR message\r\n
 *   Integer       → :42\r\n
 *   BulkString    → $5\r\nhello\r\n
 *   Array         → *2\r\n ... elements ...
 *   Null          → _\r\n          (RESP3)
 *   Boolean       → #t\r\n / #f\r\n (RESP3)
 * </pre>
 */
public sealed interface Resp3Value permits
        Resp3Value.SimpleString,
        Resp3Value.SimpleError,
        Resp3Value.RespInteger,
        Resp3Value.BulkString,
        Resp3Value.RespArray,
        Resp3Value.RespNull,
        Resp3Value.RespBoolean,
        Resp3Value.RespMap {

    /** +OK, +PONG, etc. */
    record SimpleString(String value) implements Resp3Value { }

    /** -ERR unknown command, etc. */
    record SimpleError(String message) implements Resp3Value { }

    /** :42 */
    record RespInteger(long value) implements Resp3Value { }

    /** $5\r\nhello — nullable for null bulk string ($-1\r\n in RESP2). Use RespNull in RESP3. */
    record BulkString(byte[] value) implements Resp3Value { }

    /** *N followed by N elements */
    record RespArray(List<Resp3Value> elements) implements Resp3Value { }

    /** _ — RESP3 null */
    record RespNull() implements Resp3Value { }

    /** #t or #f — RESP3 boolean */
    record RespBoolean(boolean value) implements Resp3Value { }

    /** %N followed by N key-value pairs — RESP3 map */
    record RespMap(Map<Resp3Value, Resp3Value> entries) implements Resp3Value { }

    // --- convenience factories ---

    /** OK simple string. */
    static SimpleString ok() {
        return new SimpleString("OK");
    }

    /** PONG simple string. */
    static SimpleString pong() {
        return new SimpleString("PONG");
    }

    /** Generic error. */
    static SimpleError error(final String message) {
        return new SimpleError(message);
    }

    /** Wrong number of arguments error. */
    static SimpleError wrongArity(final String command) {
        return new SimpleError("ERR wrong number of arguments for '" + command + "' command");
    }

    /** RESP3 null. */
    static RespNull nil() {
        return new RespNull();
    }
}

package io.kiradb.client;

import io.kiradb.client.protocol.RespValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Package-private decoding helpers shared by the fluent client facades —
 * shape assertions on reply values plus map-reply flattening.
 */
final class Replies {

    private Replies() {
    }

    /** Expect a simple-string reply and return its text. */
    static String asSimple(final RespValue reply) {
        if (reply instanceof RespValue.Simple s) {
            return s.value();
        }
        throw unexpected("simple string", reply);
    }

    /** Expect an integer reply and return its value. */
    static long asLong(final RespValue reply) {
        if (reply instanceof RespValue.Int i) {
            return i.value();
        }
        throw unexpected("integer", reply);
    }

    /** Expect a bulk-string reply and return it as UTF-8 (null for Nil). */
    static String asStringOrNull(final RespValue reply) {
        return switch (reply) {
            case RespValue.Bulk b -> b.asString();
            case RespValue.Nil ignored -> null;
            default -> throw unexpected("bulk string or nil", reply);
        };
    }

    /** Expect a map reply and flatten it to {@code String → RespValue}. */
    static Map<String, RespValue> asMap(final RespValue reply) {
        if (!(reply instanceof RespValue.MapValue m)) {
            throw unexpected("map", reply);
        }
        Map<String, RespValue> out = new LinkedHashMap<>();
        for (Map.Entry<RespValue, RespValue> e : m.entries().entrySet()) {
            out.put(scalarToString(e.getKey()), e.getValue());
        }
        return out;
    }

    /** Read a required integer field from a flattened map reply. */
    static long mapLong(final Map<String, RespValue> map, final String key) {
        RespValue v = map.get(key);
        if (v == null) {
            throw new KiraDBException("server reply missing expected field '" + key + "'");
        }
        return asLong(v);
    }

    /**
     * Read a numeric-ish field that the server formats as a string
     * (e.g. conversion rates, {@code "n/a"} when undefined → {@code -1.0}).
     */
    static double mapRate(final Map<String, RespValue> map, final String key) {
        RespValue v = map.get(key);
        if (v == null) {
            throw new KiraDBException("server reply missing expected field '" + key + "'");
        }
        String s = scalarToString(v);
        return "n/a".equals(s) ? -1.0 : Double.parseDouble(s);
    }

    /** Render a scalar reply (simple/bulk/int/bool) as a string. */
    static String scalarToString(final RespValue value) {
        return switch (value) {
            case RespValue.Simple s -> s.value();
            case RespValue.Bulk b -> b.asString();
            case RespValue.Int i -> Long.toString(i.value());
            case RespValue.Bool b -> Boolean.toString(b.value());
            default -> throw unexpected("scalar", value);
        };
    }

    private static KiraDBException unexpected(final String expected, final RespValue actual) {
        return new KiraDBException(
                "unexpected server reply: wanted " + expected + ", got "
                        + actual.getClass().getSimpleName() + " (" + actual + ")");
    }
}

package io.kiradb.server.command;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Immutable representation of a parsed RESP3 command.
 *
 * <p>A record in Java 25 — the compiler auto-generates constructor, accessors,
 * {@code equals}, {@code hashCode}, and {@code toString}. Perfect for immutable
 * data carriers like this.
 *
 * <p>Example: {@code SET hello world} → {@code Command("SET", ["hello", "world"])}
 *
 * @param name the command name, always upper-cased (e.g. "SET", "GET")
 * @param args the arguments as raw byte arrays (preserves binary values)
 */
public record Command(String name, List<byte[]> args) {

    /**
     * Return argument at position {@code index} as a UTF-8 string.
     *
     * @param index 0-based argument index
     * @return the argument as a string
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public String argAsString(final int index) {
        return new String(args.get(index), StandardCharsets.UTF_8);
    }

    /**
     * Return argument at position {@code index} as raw bytes.
     *
     * @param index 0-based argument index
     * @return the argument bytes
     */
    public byte[] argAsBytes(final int index) {
        return args.get(index);
    }

    /**
     * Convenience — number of arguments (not counting the command name).
     *
     * @return argument count
     */
    public int arity() {
        return args.size();
    }
}

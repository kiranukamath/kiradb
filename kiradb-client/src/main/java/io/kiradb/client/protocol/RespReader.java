package io.kiradb.client.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocking RESP3 reply parser over an {@link InputStream}.
 *
 * <p>The reader is deliberately built on plain blocking I/O: a TCP stream may
 * deliver a reply in arbitrarily small fragments, and with blocking reads the
 * "partial frame" problem disappears — {@code read()} simply blocks until the
 * next byte arrives. Combined with virtual threads (one per connection),
 * blocking is cheap and the parser stays a straightforward recursive descent
 * with no resumable-state machinery (contrast with the server's Netty decoder,
 * which must handle partial buffers explicitly).
 *
 * <p>Understands all reply types the KiraDB server emits ({@code + - : $ * _ # %})
 * plus the RESP3 push type ({@code >}) for out-of-band frames.
 */
public final class RespReader {

    private final InputStream in;

    /**
     * Wrap an input stream. The caller should pass a buffered stream — this
     * class reads one byte at a time for line-delimited fields.
     *
     * @param in the stream to read replies from
     */
    public RespReader(final InputStream in) {
        this.in = in;
    }

    /**
     * Read one complete RESP3 value, blocking until it is fully available.
     *
     * @return the parsed value
     * @throws IOException if the stream ends mid-frame or an I/O error occurs
     */
    public RespValue read() throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException("connection closed by server");
        }
        return switch ((char) type) {
            case '+' -> new RespValue.Simple(readLine());
            case '-' -> new RespValue.Error(readLine());
            case ':' -> new RespValue.Int(Long.parseLong(readLine()));
            case '$' -> readBulk();
            case '*' -> readAggregate(false);
            case '>' -> readAggregate(true);
            case '_' -> readNil();
            case '#' -> new RespValue.Bool("t".equals(readLine()));
            case '%' -> readMap();
            default -> throw new IOException(
                    "protocol error: unknown reply type byte '" + (char) type + "'");
        };
    }

    private RespValue readBulk() throws IOException {
        int length = Integer.parseInt(readLine());
        if (length < 0) {
            return new RespValue.Nil(); // RESP2 null bulk string ($-1)
        }
        byte[] data = readExactly(length);
        expectCrlf();
        return new RespValue.Bulk(data);
    }

    private RespValue readAggregate(final boolean push) throws IOException {
        int count = Integer.parseInt(readLine());
        if (count < 0) {
            return new RespValue.Nil(); // RESP2 null array (*-1)
        }
        List<RespValue> elements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            elements.add(read());
        }
        return push ? new RespValue.Push(elements) : new RespValue.Array(elements);
    }

    private RespValue readMap() throws IOException {
        int count = Integer.parseInt(readLine());
        Map<RespValue, RespValue> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            RespValue key = read();
            RespValue value = read();
            entries.put(key, value);
        }
        return new RespValue.MapValue(entries);
    }

    private RespValue readNil() throws IOException {
        readLine(); // consume the (empty) rest of the line
        return new RespValue.Nil();
    }

    /** Reads bytes up to (but not including) CRLF, consuming the CRLF. */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder(16);
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("connection closed mid-line");
            }
            if (b == '\r') {
                int lf = in.read();
                if (lf != '\n') {
                    throw new IOException("protocol error: expected LF after CR, got " + lf);
                }
                return sb.toString();
            }
            sb.append((char) b);
        }
    }

    private byte[] readExactly(final int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(data, offset, length - offset);
            if (n < 0) {
                throw new EOFException("connection closed mid-bulk-string");
            }
            offset += n;
        }
        return data;
    }

    private void expectCrlf() throws IOException {
        int cr = in.read();
        int lf = in.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("protocol error: expected CRLF after bulk payload");
        }
    }

    /**
     * Decode a bulk-string line's UTF-8 bytes. Exposed for symmetry with the writer.
     *
     * @param data raw payload bytes
     * @return the UTF-8 string
     */
    public static String utf8(final byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }
}

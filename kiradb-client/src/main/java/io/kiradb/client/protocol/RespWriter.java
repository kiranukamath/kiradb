package io.kiradb.client.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes client commands as RESP arrays of bulk strings.
 *
 * <p>Every command a Redis-protocol client sends — regardless of RESP version —
 * has the same shape: an array of bulk strings. {@code SET hello world} becomes:
 * <pre>
 *   *3\r\n$3\r\nSET\r\n$5\r\nhello\r\n$5\r\nworld\r\n
 * </pre>
 * Bulk strings carry an explicit byte length, so arguments may contain any
 * bytes, including CR/LF and NUL — no escaping needed. That is the whole trick
 * of the protocol and why it is so easy to implement correctly.
 */
public final class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};

    private final OutputStream out;

    /**
     * Wrap an output stream. Pass a buffered stream — the writer emits many
     * small writes and flushes once per command.
     *
     * @param out the stream to write commands to
     */
    public RespWriter(final OutputStream out) {
        this.out = out;
    }

    /**
     * Write one command (name + arguments) as a RESP array of bulk strings and flush.
     *
     * @param parts the command name followed by its arguments
     * @throws IOException if the socket write fails
     */
    public void writeCommand(final List<byte[]> parts) throws IOException {
        out.write('*');
        out.write(ascii(Integer.toString(parts.size())));
        out.write(CRLF);
        for (byte[] part : parts) {
            out.write('$');
            out.write(ascii(Integer.toString(part.length)));
            out.write(CRLF);
            out.write(part);
            out.write(CRLF);
        }
        out.flush();
    }

    private static byte[] ascii(final String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}

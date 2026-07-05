package io.kiradb.client.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One blocking TCP connection to a KiraDB node speaking RESP3.
 *
 * <p>Deliberately blocking-I/O: one socket, one in-flight command at a time.
 * Blocking keeps the code obviously correct (no callback state machines) and
 * virtual threads make it cheap — a pool of blocked connections costs a few KB
 * each, not an OS thread each. Concurrency comes from the connection pool, not
 * from multiplexing a single connection.
 *
 * <p>Not thread-safe. A connection must be used by one thread at a time; the
 * {@code ConnectionPool} enforces this by handing a borrowed connection to
 * exactly one caller.
 */
public final class RespConnection implements AutoCloseable {

    private final Socket socket;
    private final RespReader reader;
    private final RespWriter writer;

    /**
     * Open a connection.
     *
     * @param host             host name or address of the node
     * @param port             RESP3 port of the node
     * @param connectTimeoutMs TCP connect timeout in milliseconds
     * @param readTimeoutMs    socket read timeout ({@code SO_TIMEOUT}) in milliseconds;
     *                         0 means block forever (used by subscriber connections
     *                         that legitimately sit idle waiting for push frames)
     * @throws IOException if the connection cannot be established
     */
    public RespConnection(
            final String host,
            final int port,
            final int connectTimeoutMs,
            final int readTimeoutMs) throws IOException {
        this.socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(readTimeoutMs);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        this.reader = new RespReader(new BufferedInputStream(socket.getInputStream()));
        this.writer = new RespWriter(new BufferedOutputStream(socket.getOutputStream()));
    }

    /**
     * Send a command without reading its reply. Pair with {@link #readReply()}.
     * Subscriber connections use this split because a dedicated reader thread
     * owns all reads on the socket.
     *
     * @param args command name followed by arguments, UTF-8 encoded
     * @throws IOException if the write fails
     */
    public void sendCommand(final String... args) throws IOException {
        List<byte[]> parts = new ArrayList<>(args.length);
        for (String arg : args) {
            parts.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        writer.writeCommand(parts);
    }

    /**
     * Read the next frame from the socket — either a command reply or a push frame.
     *
     * @return the parsed value
     * @throws IOException if the read fails or the connection is closed
     */
    public RespValue readReply() throws IOException {
        return reader.read();
    }

    /**
     * Send a command and block for its reply. Any RESP3 {@code >} push frames
     * that arrive first are skipped — pooled request/response connections never
     * subscribe to anything, so a stray push has no listener here (subscriptions
     * live on a dedicated connection, mirroring how Redis clients isolate pub/sub).
     *
     * @param args command name followed by arguments, UTF-8 encoded
     * @return the reply value (may be an {@link RespValue.Error}; caller maps to exceptions)
     * @throws IOException if the round trip fails
     */
    public RespValue command(final String... args) throws IOException {
        sendCommand(args);
        RespValue reply = readReply();
        while (reply instanceof RespValue.Push) {
            reply = readReply();
        }
        return reply;
    }

    /**
     * Health check: send {@code PING} and verify the {@code +PONG} reply.
     *
     * @return true if the connection answered PING correctly
     */
    public boolean ping() {
        try {
            return command("PING") instanceof RespValue.Simple s && "PONG".equals(s.value());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the underlying socket is still open (does not detect half-closed
     * peers — use {@link #ping()} for a real liveness check).
     *
     * @return true if the socket has not been closed locally
     */
    public boolean isOpen() {
        return !socket.isClosed();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort close
        }
    }
}

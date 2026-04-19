package io.kiradb.raft.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * TCP client that sends Raft RPC messages to a single peer.
 *
 * <p>Each {@link RaftRpcClient} maintains a persistent TCP connection to one peer.
 * If the connection drops, it reconnects on the next call. This avoids the overhead
 * of a new TCP handshake per heartbeat (which fires every 50ms).
 *
 * <p>Calls are synchronous and blocking — the caller holds the Raft lock while sending
 * and waiting for a response. In production you would make these async, but synchronous
 * is simpler and correct for a learning implementation.
 */
public final class RaftRpcClient implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(RaftRpcClient.class);
    private static final int CONNECT_TIMEOUT_MS = 200;
    private static final int READ_TIMEOUT_MS    = 500;

    private final String peerId;
    private final String host;
    private final int    port;
    private final ObjectMapper mapper;

    private Socket         socket;
    private PrintWriter    writer;
    private BufferedReader reader;

    /**
     * Create a client for a single peer.
     *
     * @param peerId  peer's node identifier (for logging)
     * @param address peer's {@code "host:raftPort"} address
     */
    public RaftRpcClient(final String peerId, final String address) {
        this.peerId = peerId;
        String[] parts = address.split(":");
        this.host   = parts[0];
        this.port   = Integer.parseInt(parts[1]);
        this.mapper = new ObjectMapper();
    }

    /**
     * Send an RPC to the peer and return the response.
     * Returns {@code null} if the peer is unreachable or the call times out.
     *
     * @param rpc the RPC message to send
     * @return the response, or {@code null} on failure
     */
    public RaftRpc send(final RaftRpc rpc) {
        try {
            ensureConnected();
            writer.println(mapper.writeValueAsString(rpc));
            String response = reader.readLine();
            if (response == null) {
                disconnect();
                return null;
            }
            return mapper.readValue(response, RaftRpc.class);
        } catch (IOException e) {
            LOG.debug("RPC to {} failed: {}", peerId, e.getMessage());
            disconnect();
            return null;
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void ensureConnected() throws IOException {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        socket = new Socket();
        socket.connect(
                new java.net.InetSocketAddress(host, port),
                CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        writer = new PrintWriter(socket.getOutputStream(), true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        LOG.debug("Connected to peer {} at {}:{}", peerId, host, port);
    }

    private void disconnect() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) { }
        socket = null;
        writer = null;
        reader = null;
    }
}

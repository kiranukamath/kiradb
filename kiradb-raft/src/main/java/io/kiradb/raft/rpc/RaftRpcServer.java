package io.kiradb.raft.rpc;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * TCP server that accepts Raft RPC connections from peers.
 *
 * <p>Each peer connection is handled on its own virtual thread. Messages arrive as
 * newline-delimited JSON, are deserialized into {@link RaftRpc} objects, dispatched to
 * a handler function, and the response is serialized back as JSON.
 *
 * <p>Protocol:
 * <pre>
 *   → {"type":"RequestVote","term":2,"candidateId":"node-1",...}\n
 *   ← {"type":"RequestVoteResponse","term":2,"voteGranted":true}\n
 * </pre>
 */
public final class RaftRpcServer implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(RaftRpcServer.class);

    private final int port;
    private final Function<RaftRpc, RaftRpc> handler;
    private final ObjectMapper mapper;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    /**
     * Create a Raft RPC server.
     *
     * @param port    the TCP port to listen on
     * @param handler function that processes an incoming RPC and returns the response
     */
    public RaftRpcServer(final int port, final Function<RaftRpc, RaftRpc> handler) {
        this.port    = port;
        this.handler = handler;
        this.mapper  = new ObjectMapper();
    }

    /**
     * Start listening for incoming Raft RPC connections.
     *
     * @throws IOException if the server socket cannot be bound
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        acceptThread = Thread.ofVirtual().name("raft-rpc-accept").start(this::acceptLoop);
        LOG.info("Raft RPC server listening on port {}", port);
    }

    @Override
    public void close() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) { }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Socket clientSocket = serverSocket.accept();
                Thread.ofVirtual().name("raft-rpc-conn").start(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    LOG.warn("Raft RPC accept error: {}", e.getMessage());
                }
                break;
            }
        }
    }

    private void handleConnection(final Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    RaftRpc request  = mapper.readValue(line, RaftRpc.class);
                    RaftRpc response = handler.apply(request);
                    writer.println(mapper.writeValueAsString(response));
                } catch (Exception e) {
                    LOG.error("RPC handler error: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.debug("Raft RPC connection closed: {}", e.getMessage());
        }
    }
}

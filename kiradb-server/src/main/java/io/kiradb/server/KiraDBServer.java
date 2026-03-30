package io.kiradb.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for KiraDB.
 *
 * <p>Bootstraps the server, binds to the client port (6379),
 * the Raft inter-node port (7379), and the metrics/dashboard port (8080).
 */
public final class KiraDBServer {

    private static final Logger LOG = LoggerFactory.getLogger(KiraDBServer.class);

    private KiraDBServer() {
        // utility class — instantiation not allowed
    }

    /**
     * Main entry point.
     *
     * @param args command-line arguments (unused for now)
     */
    public static void main(final String[] args) {
        LOG.info("KiraDB starting...");
        LOG.info("Client port  : 6379  (RESP3)");
        LOG.info("Raft port    : 7379  (internal)");
        LOG.info("Metrics port : 8080  (HTTP)");
        LOG.info("Storage      : in-memory (Phase 1)");
    }
}

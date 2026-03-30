package io.kiradb.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test — proves the test framework is wired correctly.
 * Real integration tests arrive in Phase 2 when the RESP3 server is live.
 */
class KiraDBServerTest {

    @Test
    void serverClassLoads() {
        // If this class is reachable, the module compiled correctly.
        assertTrue(true, "KiraDB server module compiled and tests are running");
    }
}

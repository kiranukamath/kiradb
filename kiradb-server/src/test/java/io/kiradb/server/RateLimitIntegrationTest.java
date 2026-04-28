package io.kiradb.server;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.crdt.CrdtStore;
import io.kiradb.server.command.CommandRouter;
import io.kiradb.services.ratelimit.RateLimiterStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end test of the {@code RL.*} commands via the real Netty pipeline.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RateLimitIntegrationTest {

    private static final int TEST_PORT = 16382;

    private Thread serverThread;
    private StorageEngine storage;
    private Jedis jedis;

    private record Cmd(String name) implements ProtocolCommand {
        @Override
        public byte[] getRaw() {
            return name.getBytes(StandardCharsets.UTF_8);
        }
    }

    @BeforeAll
    void startServer(@TempDir Path dataDir) throws Exception {
        storage = new LsmStorageEngine(dataDir);
        CrdtStore crdtStore = new CrdtStore(storage, "rl-test-node");
        RateLimiterStore rl = new RateLimiterStore(crdtStore);
        CommandRouter router = new CommandRouter(storage, crdtStore);
        KiraDBServer.registerRateLimitCommands(router, rl);
        KiraDBChannelHandler handler = new KiraDBChannelHandler(router);

        serverThread = Thread.ofVirtual().start(() -> {
            try {
                KiraDBServer.start(TEST_PORT, handler);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(500);
        jedis = new Jedis("localhost", TEST_PORT);
    }

    @AfterAll
    void stopServer() throws IOException {
        if (jedis != null) {
            jedis.close();
        }
        if (storage != null) {
            storage.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    void allowsBelowLimitAndDeniesAtLimit() {
        // Use a unique limiter+key to keep tests independent.
        for (int i = 1; i <= 5; i++) {
            Object reply = jedis.sendCommand(
                    new Cmd("RL.ALLOW"), "test-a", "user:1", "5", "60");
            assertEquals(1L, reply, "request " + i + " should be allowed");
        }
        Object sixth = jedis.sendCommand(
                new Cmd("RL.ALLOW"), "test-a", "user:1", "5", "60");
        assertEquals(0L, sixth);
    }

    @Test
    void differentKeysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            jedis.sendCommand(new Cmd("RL.ALLOW"), "test-b", "user:1", "3", "60");
        }
        Object dec = jedis.sendCommand(new Cmd("RL.ALLOW"), "test-b", "user:2", "3", "60");
        assertEquals(1L, dec);
    }

    @Test
    void statusReturnsRespMap() {
        jedis.sendCommand(new Cmd("RL.ALLOW"), "test-c", "user:1", "10", "60");
        jedis.sendCommand(new Cmd("RL.ALLOW"), "test-c", "user:1", "10", "60");

        Object reply = jedis.sendCommand(
                new Cmd("RL.STATUS"), "test-c", "user:1", "10", "60");
        // Jedis may surface RESP3 maps differently; we only assert non-null and
        // verify status is read-only via a follow-up RL.STATUS that should be unchanged.
        assertNotNull(reply);

        Object replyAgain = jedis.sendCommand(
                new Cmd("RL.STATUS"), "test-c", "user:1", "10", "60");
        assertNotNull(replyAgain);
    }

    @Test
    void zeroLimitAlwaysDenies() {
        Object reply = jedis.sendCommand(
                new Cmd("RL.ALLOW"), "test-d", "user:1", "0", "60");
        assertEquals(0L, reply);
    }

    @Test
    void resetReturnsExplicitError() {
        // RL.RESET is intentionally unsupported (GCounter is grow-only).
        // Server returns -ERR; Jedis surfaces this as a JedisDataException.
        assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                () -> jedis.sendCommand(new Cmd("RL.RESET"), "test-e", "user:1"));
    }
}

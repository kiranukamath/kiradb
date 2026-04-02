package io.kiradb.server;

import io.kiradb.server.command.CommandRouter;
import io.kiradb.server.storage.InMemoryStorageEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import redis.clients.jedis.Jedis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test — starts a real KiraDB server on a random port,
 * connects with the Jedis Redis client, and exercises all Phase 2 commands.
 *
 * <p>This is the milestone check: if these pass, {@code redis-cli SET hello world} works.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KiraDBServerTest {

    private static final int TEST_PORT = 16379; // avoid conflict with local Redis

    private Thread serverThread;
    private Jedis jedis;

    @BeforeAll
    void startServer() throws Exception {
        InMemoryStorageEngine storage = new InMemoryStorageEngine();
        CommandRouter router = new CommandRouter(storage);
        KiraDBChannelHandler handler = new KiraDBChannelHandler(router);

        serverThread = Thread.ofVirtual().start(() -> {
            try {
                KiraDBServer.start(TEST_PORT, handler);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Give Netty a moment to bind
        Thread.sleep(500);
        jedis = new Jedis("localhost", TEST_PORT);
    }

    @AfterAll
    void stopServer() {
        if (jedis != null) {
            jedis.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    void ping() {
        assertEquals("PONG", jedis.ping());
    }

    @Test
    void setAndGet() {
        jedis.set("hello", "world");
        assertEquals("world", jedis.get("hello"));
    }

    @Test
    void getMissingKeyReturnsNull() {
        assertNull(jedis.get("no-such-key-xyz"));
    }

    @Test
    void del() {
        jedis.set("to-delete", "value");
        assertEquals(1L, jedis.del("to-delete"));
        assertNull(jedis.get("to-delete"));
    }

    @Test
    void delMultipleKeys() {
        jedis.set("k1", "v1");
        jedis.set("k2", "v2");
        assertEquals(2L, jedis.del("k1", "k2"));
    }

    @Test
    void exists() {
        jedis.set("exists-key", "v");
        assertTrue(jedis.exists("exists-key"));
        assertFalse(jedis.exists("missing-key-xyz"));
    }

    @Test
    void setWithExpiry() throws InterruptedException {
        jedis.setex("expiring", 1, "bye");
        assertEquals("bye", jedis.get("expiring"));
        Thread.sleep(1100);
        assertNull(jedis.get("expiring"));
    }

    @Test
    void ttlNoExpiry() {
        jedis.set("no-ttl", "value");
        assertEquals(-1L, jedis.ttl("no-ttl"));
    }

    @Test
    void ttlMissingKey() {
        assertEquals(-2L, jedis.ttl("definitely-missing-xyz"));
    }

    @Test
    void expireAndTtl() throws InterruptedException {
        jedis.set("with-expire", "value");
        jedis.expire("with-expire", 10L);
        long ttl = jedis.ttl("with-expire");
        assertTrue(ttl > 0 && ttl <= 10, "TTL should be between 1 and 10, got " + ttl);
    }

    @Test
    void overwriteKey() {
        jedis.set("overwrite", "first");
        jedis.set("overwrite", "second");
        assertEquals("second", jedis.get("overwrite"));
    }
}

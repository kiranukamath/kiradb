package io.kiradb.server;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.lsm.LsmStorageEngine;
import io.kiradb.server.command.CommandRouter;
import io.kiradb.server.config.ConfigSubscriptionRegistry;
import io.kiradb.services.config.ConfigStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.util.SafeEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the {@code CFG.*} commands via the real Netty pipeline,
 * including the server-push notification path triggered by {@code CFG.WATCH}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigIntegrationTest {

    private static final int TEST_PORT = 16383;

    private Thread serverThread;
    private StorageEngine storage;
    private ConfigSubscriptionRegistry registry;
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
        ConfigStore configStore = new ConfigStore(storage);
        CommandRouter router = new CommandRouter(storage);
        registry = KiraDBServer.registerConfigCommands(router, configStore);
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
    void setReturnsVersionNumber() {
        Object reply = jedis.sendCommand(
                new Cmd("CFG.SET"), "payment-service", "timeout", "3000");
        assertEquals(1L, reply);

        Object reply2 = jedis.sendCommand(
                new Cmd("CFG.SET"), "payment-service", "timeout", "5000");
        assertEquals(2L, reply2);
    }

    @Test
    void getReturnsLatestValue() {
        jedis.sendCommand(new Cmd("CFG.SET"), "svc-a", "k", "first");
        jedis.sendCommand(new Cmd("CFG.SET"), "svc-a", "k", "second");

        Object reply = jedis.sendCommand(new Cmd("CFG.GET"), "svc-a", "k");
        assertEquals("second", SafeEncoder.encode((byte[]) reply));
    }

    @Test
    void getReturnsNilForAbsent() {
        Object reply = jedis.sendCommand(new Cmd("CFG.GET"), "svc-a", "no-such-key");
        assertEquals(null, reply);
    }

    @Test
    void historyContainsAllVersions() {
        jedis.sendCommand(new Cmd("CFG.SET"), "svc-h", "k", "v1");
        jedis.sendCommand(new Cmd("CFG.SET"), "svc-h", "k", "v2");
        jedis.sendCommand(new Cmd("CFG.SET"), "svc-h", "k", "v3");

        Object reply = jedis.sendCommand(new Cmd("CFG.HIST"), "svc-h", "k");
        // Reply is a RESP array of RESP maps (one per version).
        // Jedis decodes RESP3 maps into List of alternating key/value byte arrays.
        // We just check the array length matches the number of versions.
        assertNotNull(reply);
        assertTrue(reply instanceof List);
        assertEquals(3, ((List<?>) reply).size());
    }

    @Test
    void watchReceivesPushNotificationOnChange() throws Exception {
        // Use a SECOND Jedis connection as the subscriber so the main one stays
        // free to push CFG.SET writes.
        Jedis sub = new Jedis("localhost", TEST_PORT);
        try {
            // Subscribe
            Object watchReply = sub.sendCommand(new Cmd("CFG.WATCH"), "watched-svc");
            assertEquals("OK", SafeEncoder.encode((byte[]) watchReply));

            // Subscriber count visible via the registry
            // (Wait briefly for the closeFuture/listener attach to complete.)
            for (int i = 0; i < 10 && registry.subscriberCount("watched-svc") == 0; i++) {
                Thread.sleep(20);
            }
            assertTrue(registry.subscriberCount("watched-svc") >= 1);

            // Trigger a change on the writer connection.
            jedis.sendCommand(new Cmd("CFG.SET"), "watched-svc", "k", "pushed-value");

            // Read the next frame off the subscriber. Use a background thread + latch
            // because Jedis' getOne() blocks; we want a timeout.
            CountDownLatch gotFrame = new CountDownLatch(1);
            AtomicReference<Object> received = new AtomicReference<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                Object frame = sub.getConnection().getOne();
                received.set(frame);
                gotFrame.countDown();
            });
            assertTrue(gotFrame.await(2, TimeUnit.SECONDS),
                    "expected a CFG.NOTIFY push within 2 seconds");

            Object frame = received.get();
            assertNotNull(frame);
            assertTrue(frame instanceof List);
            List<?> elements = (List<?>) frame;
            assertEquals("CFG.NOTIFY", SafeEncoder.encode((byte[]) elements.get(0)));
            assertEquals("watched-svc", SafeEncoder.encode((byte[]) elements.get(1)));
            assertEquals("k",          SafeEncoder.encode((byte[]) elements.get(2)));
            assertEquals("pushed-value", SafeEncoder.encode((byte[]) elements.get(3)));

            reader.join();
        } finally {
            sub.close();
        }

        // After close, the registry should auto-clean the subscription.
        for (int i = 0; i < 20 && registry.subscriberCount("watched-svc") > 0; i++) {
            Thread.sleep(25);
        }
        assertEquals(0, registry.subscriberCount("watched-svc"),
                "subscription should be cleaned up after channel close");
    }

    @Test
    void unwatchRemovesSubscription() throws Exception {
        Jedis sub = new Jedis("localhost", TEST_PORT);
        try {
            sub.sendCommand(new Cmd("CFG.WATCH"), "scope-u");
            for (int i = 0; i < 10 && registry.subscriberCount("scope-u") == 0; i++) {
                Thread.sleep(20);
            }
            assertTrue(registry.subscriberCount("scope-u") >= 1);

            Object reply = sub.sendCommand(new Cmd("CFG.UNWATCH"), "scope-u");
            assertEquals(1L, reply);

            // After unsubscribe, count should drop.
            for (int i = 0; i < 10 && registry.subscriberCount("scope-u") > 0; i++) {
                Thread.sleep(20);
            }
            assertEquals(0, registry.subscriberCount("scope-u"));
        } finally {
            sub.close();
        }
    }
}

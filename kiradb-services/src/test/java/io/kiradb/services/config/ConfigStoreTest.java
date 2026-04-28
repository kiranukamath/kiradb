package io.kiradb.services.config;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.StorageEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigStoreTest {

    private FakeStorage storage;
    private ConfigStore configs;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        configs = new ConfigStore(storage);
    }

    @Test
    void setThenGet() {
        configs.set("payment-service", "timeout", "3000");
        assertEquals(Optional.of("3000"), configs.get("payment-service", "timeout"));
    }

    @Test
    void historyAccumulates() {
        configs.set("svc", "k", "v1");
        configs.set("svc", "k", "v2");
        configs.set("svc", "k", "v3");

        List<ConfigVersion> hist = configs.history("svc", "k");
        assertEquals(3, hist.size());
        assertEquals("v1", hist.get(0).value());
        assertEquals("v2", hist.get(1).value());
        assertEquals("v3", hist.get(2).value());
        assertEquals(1L, hist.get(0).versionNumber());
        assertEquals(3L, hist.get(2).versionNumber());
    }

    @Test
    void latestVersionMatchesGet() {
        configs.set("svc", "k", "v1");
        configs.set("svc", "k", "v2");
        ConfigVersion latest = configs.latestVersion("svc", "k").orElseThrow();
        assertEquals("v2", latest.value());
        assertEquals(2L, latest.versionNumber());
    }

    @Test
    void absentKeyReturnsEmpty() {
        assertTrue(configs.get("svc", "missing").isEmpty());
        assertTrue(configs.history("svc", "missing").isEmpty());
    }

    @Test
    void differentScopesAreIndependent() {
        configs.set("svc-a", "k", "a");
        configs.set("svc-b", "k", "b");
        assertEquals("a", configs.get("svc-a", "k").orElseThrow());
        assertEquals("b", configs.get("svc-b", "k").orElseThrow());
    }

    @Test
    void listenerReceivesChanges() {
        List<ConfigChange> received = new ArrayList<>();
        configs.addListener(received::add);

        configs.set("svc", "k", "v1");
        configs.set("svc", "k", "v2");

        assertEquals(2, received.size());
        assertEquals("svc", received.get(0).scope());
        assertEquals("k", received.get(0).key());
        assertEquals("v1", received.get(0).newVersion().value());
        assertEquals("v2", received.get(1).newVersion().value());
    }

    @Test
    void removedListenerStopsReceiving() {
        List<ConfigChange> received = new ArrayList<>();
        ConfigChangeListener l = received::add;
        configs.addListener(l);
        configs.set("svc", "k", "v1");
        configs.removeListener(l);
        configs.set("svc", "k", "v2");

        assertEquals(1, received.size());
        assertEquals("v1", received.get(0).newVersion().value());
    }

    @Test
    void survivesPersistenceRoundTrip() {
        configs.set("svc", "k", "first");
        configs.set("svc", "k", "second");

        // Build a fresh store over the same backing storage.
        ConfigStore reload = new ConfigStore(storage);
        assertEquals("second", reload.get("svc", "k").orElseThrow());
        List<ConfigVersion> hist = reload.history("svc", "k");
        assertEquals(2, hist.size());
        assertEquals("first", hist.get(0).value());
    }

    @Test
    void exceptionInListenerDoesNotBreakWriter() {
        configs.addListener(c -> {
            throw new RuntimeException("boom");
        });
        // The set() call should still succeed despite the listener exception.
        configs.set("svc", "k", "v1");
        assertEquals("v1", configs.get("svc", "k").orElseThrow());
    }

    @Test
    void timestampsAreMonotonicAcrossVersions() {
        configs.set("svc", "k", "v1");
        configs.set("svc", "k", "v2");
        configs.set("svc", "k", "v3");
        List<ConfigVersion> hist = configs.history("svc", "k");
        assertFalse(hist.get(1).timestampMillis() < hist.get(0).timestampMillis());
        assertFalse(hist.get(2).timestampMillis() < hist.get(1).timestampMillis());
    }

    /** In-memory StorageEngine — same shape as elsewhere in the codebase. */
    private static final class FakeStorage implements StorageEngine {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

        private static String k(final byte[] key) {
            return new String(key, StandardCharsets.UTF_8);
        }

        @Override
        public void put(final byte[] key, final byte[] value) {
            store.put(k(key), value);
        }

        @Override
        public void put(final byte[] key, final byte[] value, final long expiryMillis) {
            store.put(k(key), value);
        }

        @Override
        public Optional<byte[]> get(final byte[] key) {
            return Optional.ofNullable(store.get(k(key)));
        }

        @Override
        public boolean delete(final byte[] key) {
            return store.remove(k(key)) != null;
        }

        @Override
        public boolean exists(final byte[] key) {
            return store.containsKey(k(key));
        }

        @Override
        public long ttlMillis(final byte[] key) {
            return store.containsKey(k(key)) ? -1 : -2;
        }

        @Override
        public boolean expire(final byte[] key, final long expiryMillis) {
            return false;
        }

        @Override
        public Iterator<StorageEntry> scan(final byte[] startKey, final byte[] endKey) {
            return new TreeMap<>(store).entrySet().stream()
                    .map(e -> StorageEntry.live(
                            e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue()))
                    .map(se -> (StorageEntry) se)
                    .iterator();
        }

        @Override
        public void close() { }
    }
}

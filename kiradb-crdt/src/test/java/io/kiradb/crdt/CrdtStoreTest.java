package io.kiradb.crdt;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.StorageEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrdtStoreTest {

    @Test
    void gCounterIncrementPersistsAndReloads() {
        FakeStorage storage = new FakeStorage();
        CrdtStore s1 = new CrdtStore(storage, "node1");
        s1.gCounterIncrement("votes", 5);
        s1.gCounterIncrement("votes", 3);
        assertEquals(8, s1.gCounterValue("votes"));

        // New CrdtStore over the same storage — must reload state.
        CrdtStore s2 = new CrdtStore(storage, "node1");
        assertEquals(8, s2.gCounterValue("votes"));
    }

    @Test
    void mergeGCounterAcceptsRemoteState() {
        FakeStorage storage = new FakeStorage();
        CrdtStore local = new CrdtStore(storage, "local");
        local.gCounterIncrement("votes", 4);

        // A peer's serialized counter (from a different node).
        GCounter remote = new GCounter("peer");
        remote.increment(7);
        local.mergeGCounter("votes", remote.serialize());

        assertEquals(11, local.gCounterValue("votes"));

        // And the merge persisted: a fresh store sees the merged total.
        CrdtStore reload = new CrdtStore(storage, "local");
        assertEquals(11, reload.gCounterValue("votes"));
    }

    @Test
    void mergePnCounterAcceptsRemoteStateAndPersists() {
        FakeStorage storage = new FakeStorage();
        CrdtStore local = new CrdtStore(storage, "local");
        local.pnCounterAdd("balance", 100);

        PNCounter remote = new PNCounter("peer");
        remote.add(50);
        remote.add(-20);
        local.mergePnCounter("balance", remote.serialize());

        assertEquals(130, local.pnCounterValue("balance"));

        CrdtStore reload = new CrdtStore(storage, "local");
        assertEquals(130, reload.pnCounterValue("balance"));
    }

    @Test
    void mergeLwwRegisterAcceptsRemoteStateAndPersists() {
        FakeStorage storage = new FakeStorage();
        CrdtStore local = new CrdtStore(storage, "local");
        local.lwwSet("flag", "local-value".getBytes(StandardCharsets.UTF_8));

        LWWRegister remote = new LWWRegister("peer");
        remote.set("peer-value".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis() + 60_000L);
        local.mergeLwwRegister("flag", remote.serialize());

        assertArrayEquals("peer-value".getBytes(StandardCharsets.UTF_8), local.lwwGet("flag"));

        CrdtStore reload = new CrdtStore(storage, "local");
        assertArrayEquals("peer-value".getBytes(StandardCharsets.UTF_8), reload.lwwGet("flag"));
    }

    @Test
    void mergeMvRegisterAcceptsRemoteStateAndPersists() {
        // NOTE: unlike lwwSet/orSetAdd/pnCounterAdd, CrdtStore has no persist-on-write
        // helper for MVRegister mutations today (a pre-existing gap, not introduced by
        // this change) — CrdtHandler.handleMvSet mutates the in-memory instance directly.
        // mergeMvRegister DOES persist (see below), so we drive the "before" state via
        // the in-memory register directly rather than relying on a reload to see it.
        FakeStorage storage = new FakeStorage();
        CrdtStore local = new CrdtStore(storage, "local");
        local.mvRegister("doc").set("local-edit".getBytes(StandardCharsets.UTF_8));

        MVRegister remote = new MVRegister("peer");
        remote.set("peer-edit".getBytes(StandardCharsets.UTF_8));
        local.mergeMvRegister("doc", remote.serialize());

        assertEquals(2, local.mvRegister("doc").values().size());

        // mergeMvRegister's own persistence is what a reload observes.
        CrdtStore reload = new CrdtStore(storage, "local");
        assertEquals(2, reload.mvRegister("doc").values().size());
    }

    @Test
    void mergeOrSetAcceptsRemoteStateAndPersists() {
        FakeStorage storage = new FakeStorage();
        CrdtStore local = new CrdtStore(storage, "local");
        local.orSetAdd("members", "alice");

        ORSet remote = new ORSet();
        remote.add("bob");
        local.mergeOrSet("members", remote.serialize());

        assertEquals(Set.of("alice", "bob"), local.orSet("members").elements());

        CrdtStore reload = new CrdtStore(storage, "local");
        assertEquals(Set.of("alice", "bob"), reload.orSet("members").elements());
    }

    @Test
    void pnCounterRoundTrip() {
        FakeStorage storage = new FakeStorage();
        CrdtStore s = new CrdtStore(storage, "n");
        s.pnCounterAdd("balance", 100);
        s.pnCounterAdd("balance", -30);
        assertEquals(70, s.pnCounterValue("balance"));

        CrdtStore reload = new CrdtStore(storage, "n");
        assertEquals(70, reload.pnCounterValue("balance"));
    }

    @Test
    void lwwRoundTrip() {
        FakeStorage storage = new FakeStorage();
        CrdtStore s = new CrdtStore(storage, "n");
        s.lwwSet("flag.dark-mode", "true".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("true".getBytes(StandardCharsets.UTF_8), s.lwwGet("flag.dark-mode"));

        CrdtStore reload = new CrdtStore(storage, "n");
        assertArrayEquals("true".getBytes(StandardCharsets.UTF_8),
                reload.lwwGet("flag.dark-mode"));
    }

    @Test
    void orSetRoundTrip() {
        FakeStorage storage = new FakeStorage();
        CrdtStore s = new CrdtStore(storage, "n");
        s.orSetAdd("online-users", "alice");
        s.orSetAdd("online-users", "bob");
        s.orSetRemove("online-users", "alice");

        ORSet set = s.orSet("online-users");
        assertTrue(set.contains("bob"));
        assertEquals(1, set.size());

        CrdtStore reload = new CrdtStore(storage, "n");
        ORSet reloaded = reload.orSet("online-users");
        assertEquals(set.elements(), reloaded.elements());
    }

    @Test
    void instancesAreCachedPerName() {
        FakeStorage storage = new FakeStorage();
        CrdtStore s = new CrdtStore(storage, "n");
        GCounter first = s.gCounter("c");
        GCounter again = s.gCounter("c");
        assertNotNull(first);
        assertEquals(System.identityHashCode(first), System.identityHashCode(again));
    }

    /** Minimal in-memory StorageEngine — just put/get for CRDT bytes. */
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
            TreeMap<String, byte[]> sorted = new TreeMap<>(store);
            return sorted.entrySet().stream()
                    .map(e -> StorageEntry.live(
                            e.getKey().getBytes(StandardCharsets.UTF_8), e.getValue()))
                    .map(se -> (StorageEntry) se)
                    .iterator();
        }

        @Override
        public void close() {
            // no-op
        }
    }
}

package io.kiradb.services.ratelimit;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.StorageEntry;
import io.kiradb.crdt.CrdtStore;
import io.kiradb.crdt.GCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RateLimiterStoreTest {

    private FakeStorage storage;
    private RateLimiterStore limiter;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        limiter = new RateLimiterStore(new CrdtStore(storage, "node1"));
    }

    @Test
    void allowsBelowLimit() {
        for (int i = 1; i <= 5; i++) {
            RateLimitDecision dec = limiter.allow("api", "user:1", 5, 60);
            assertTrue(dec.allowed(), "request " + i + " should be allowed");
            assertEquals(5L, dec.limit());
        }
    }

    @Test
    void deniesAtLimit() {
        for (int i = 1; i <= 5; i++) {
            limiter.allow("api", "user:1", 5, 60);
        }
        RateLimitDecision sixth = limiter.allow("api", "user:1", 5, 60);
        assertFalse(sixth.allowed(), "6th request must be denied");
        assertEquals(0L, sixth.remaining());
    }

    @Test
    void differentKeysAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.allow("api", "user:1", 5, 60);
        }
        // user:2 should still be at 0
        RateLimitDecision dec = limiter.allow("api", "user:2", 5, 60);
        assertTrue(dec.allowed());
    }

    @Test
    void differentLimitersAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.allow("api-a", "user:1", 5, 60);
        }
        // api-b is a different namespace
        RateLimitDecision dec = limiter.allow("api-b", "user:1", 5, 60);
        assertTrue(dec.allowed());
    }

    @Test
    void statusDoesNotConsume() {
        for (int i = 0; i < 3; i++) {
            limiter.allow("api", "user:1", 5, 60);
        }
        RateLimitDecision before = limiter.status("api", "user:1", 5, 60);
        assertEquals(3L, before.used());
        RateLimitDecision after = limiter.status("api", "user:1", 5, 60);
        assertEquals(3L, after.used(), "status() must not consume a slot");
    }

    @Test
    void zeroLimitAlwaysDenies() {
        RateLimitDecision dec = limiter.allow("api", "user:1", 0, 60);
        assertFalse(dec.allowed());
    }

    @Test
    void distributedEnforcementAcrossThreeNodes() {
        // The flagship test from CLAUDE.md Phase 7:
        //   "3 nodes each receiving 40 req/sec, verify total enforced at 100 req/sec"
        // Construction:
        //   - 3 separate CrdtStores, each impersonating a different node
        //   - Each independently increments its bucket 40 times via allow()
        //   - We MANUALLY merge their state (simulating gossip)
        //   - 4th request to any merged node must be DENIED because total = 121 > 100

        FakeStorage s1 = new FakeStorage();
        FakeStorage s2 = new FakeStorage();
        FakeStorage s3 = new FakeStorage();

        CrdtStore c1 = new CrdtStore(s1, "node-1");
        CrdtStore c2 = new CrdtStore(s2, "node-2");
        CrdtStore c3 = new CrdtStore(s3, "node-3");

        RateLimiterStore rl1 = new RateLimiterStore(c1);
        RateLimiterStore rl2 = new RateLimiterStore(c2);
        RateLimiterStore rl3 = new RateLimiterStore(c3);

        // Local: each node lets through 40 (no peer state yet, so all locally permitted).
        for (int i = 0; i < 40; i++) {
            assertTrue(rl1.allow("api", "user:1", 100, 60).allowed());
            assertTrue(rl2.allow("api", "user:1", 100, 60).allowed());
            assertTrue(rl3.allow("api", "user:1", 100, 60).allowed());
        }

        // Gossip simulation: merge node1's and node2's bucket state into node3.
        long bucket = rl3.currentBucketIndex("api", "user:1", 60);
        byte[] state1 = serializeBucketCounter(c1, "api", "user:1", bucket);
        byte[] state2 = serializeBucketCounter(c2, "api", "user:1", bucket);
        rl3.mergeBucket("api", "user:1", bucket, state1);
        rl3.mergeBucket("api", "user:1", bucket, state2);

        // After merge, node3 should see total = 120 (just from the merge — its
        // own 40 is already counted on node3's slot). With 120 in current bucket
        // and ~0 in previous, the 121st request must be DENIED.
        RateLimitDecision next = rl3.allow("api", "user:1", 100, 60);
        assertFalse(next.allowed(),
                "expected denial after merge — observed used=" + next.used());
        assertTrue(next.used() > 100,
                "expected used > 100 after merge — got " + next.used());
    }

    /** Serialize a specific bucket's GCounter for cross-node merge in tests. */
    private static byte[] serializeBucketCounter(
            final CrdtStore store, final String limiter, final String key, final long bucket) {
        String name = "rl:" + limiter + ":" + key + ":" + bucket;
        // Reach in via CrdtStore's API: gCounter() returns the cached/loaded counter for that name.
        GCounter c = store.gCounter(name);
        return c.serialize();
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

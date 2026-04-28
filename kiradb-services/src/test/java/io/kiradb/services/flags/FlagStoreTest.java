package io.kiradb.services.flags;

import io.kiradb.core.storage.StorageEngine;
import io.kiradb.core.storage.StorageEntry;
import io.kiradb.crdt.CrdtStore;
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

final class FlagStoreTest {

    private FlagStore flags;

    @BeforeEach
    void setUp() {
        FakeStorage storage = new FakeStorage();
        CrdtStore crdt = new CrdtStore(storage, "node1");
        flags = new FlagStore(crdt);
    }

    @Test
    void unknownFlagIsDisabledAndNoStatsRecorded() {
        assertFalse(flags.isEnabled("missing-flag", "alice"));
        FlagStats s = flags.stats("missing-flag");
        assertEquals(0L, s.enabledImpressions());
        assertEquals(0L, s.disabledImpressions());
    }

    @Test
    void fullyEnabledFlagReturnsTrueForAllUsers() {
        flags.set(FeatureFlag.fullyEnabled("dark-mode"));
        for (int i = 0; i < 100; i++) {
            assertTrue(flags.isEnabled("dark-mode", "user-" + i));
        }
    }

    @Test
    void killOverridesRollout() {
        flags.set(new FeatureFlag("checkout-v2", false, 1.0));
        assertTrue(flags.isEnabled("checkout-v2", "alice"));
        flags.kill("checkout-v2");
        assertFalse(flags.isEnabled("checkout-v2", "alice"));
    }

    @Test
    void unkillRestoresPreviousRollout() {
        flags.set(new FeatureFlag("f", false, 1.0));
        flags.kill("f");
        flags.unkill("f");
        assertTrue(flags.isEnabled("f", "alice"));
    }

    @Test
    void impressionsAreSplitByCohort() {
        // 50% rollout — both cohorts should accumulate impressions over many users.
        flags.set(new FeatureFlag("ab", false, 0.5));
        for (int i = 0; i < 1000; i++) {
            flags.isEnabled("ab", "user-" + i);
        }
        FlagStats s = flags.stats("ab");
        assertTrue(s.enabledImpressions() > 0);
        assertTrue(s.disabledImpressions() > 0);
        assertEquals(1000L, s.enabledImpressions() + s.disabledImpressions());
    }

    @Test
    void conversionsAttributeToBucketCohort() {
        flags.set(new FeatureFlag("ab", false, 0.5));
        // Pick a user who is enabled and one who is disabled, drive impressions + conversions.
        String enabledUser = null;
        String disabledUser = null;
        for (int i = 0; i < 1000 && (enabledUser == null || disabledUser == null); i++) {
            String u = "u-" + i;
            boolean e = FlagBucketing.isEnabled("ab", u, 0.5);
            if (e && enabledUser == null) {
                enabledUser = u;
            } else if (!e && disabledUser == null) {
                disabledUser = u;
            }
        }
        flags.isEnabled("ab", enabledUser);
        flags.isEnabled("ab", disabledUser);
        flags.recordConversion("ab", enabledUser);
        flags.recordConversion("ab", disabledUser);

        FlagStats s = flags.stats("ab");
        assertEquals(1L, s.enabledConversions());
        assertEquals(1L, s.disabledConversions());
        assertEquals(1.0, s.enabledConversionRate());
        assertEquals(1.0, s.disabledConversionRate());
    }

    @Test
    void conversionRateIsNegativeOneWhenNoData() {
        FlagStats s = flags.stats("never-touched");
        assertEquals(-1.0, s.enabledConversionRate());
        assertEquals(-1.0, s.disabledConversionRate());
    }

    @Test
    void listFlagsReturnsKnownNamesSorted() {
        flags.set(FeatureFlag.fullyEnabled("zeta"));
        flags.set(FeatureFlag.fullyEnabled("alpha"));
        flags.set(FeatureFlag.fullyEnabled("beta"));
        var names = flags.listFlags();
        assertEquals(3, names.size());
        assertEquals("alpha", names.get(0));
        assertEquals("beta", names.get(1));
        assertEquals("zeta", names.get(2));
    }

    /** Minimal in-memory StorageEngine for tests — same shape as the one in CrdtStoreTest. */
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

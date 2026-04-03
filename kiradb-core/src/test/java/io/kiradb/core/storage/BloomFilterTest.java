package io.kiradb.core.storage;

import io.kiradb.core.storage.bloom.BloomFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for BloomFilter: zero false negatives, bounded false positive rate, and serialization.
 */
class BloomFilterTest {

    private static final int EXPECTED_KEYS = 10_000;
    private static final double MAX_FP_RATE = 0.02; // 2% — our filter targets 1%

    @Test
    void noFalseNegatives() {
        BloomFilter filter = new BloomFilter(EXPECTED_KEYS, 0.01);

        // Add 10k keys
        for (int i = 0; i < EXPECTED_KEYS; i++) {
            filter.add(("key-" + i).getBytes());
        }

        // Every added key must be reported as possibly present
        for (int i = 0; i < EXPECTED_KEYS; i++) {
            assertTrue(filter.mightContain(("key-" + i).getBytes()),
                    "False negative for key-" + i + " — this must never happen");
        }
    }

    @Test
    void falsePositiveRateUnderThreshold() {
        BloomFilter filter = new BloomFilter(EXPECTED_KEYS, 0.01);

        for (int i = 0; i < EXPECTED_KEYS; i++) {
            filter.add(("member-" + i).getBytes());
        }

        // Test with 10k keys that were NEVER added
        int falsePositives = 0;
        for (int i = 0; i < EXPECTED_KEYS; i++) {
            if (filter.mightContain(("nonmember-" + i).getBytes())) {
                falsePositives++;
            }
        }

        double fpRate = (double) falsePositives / EXPECTED_KEYS;
        assertTrue(fpRate < MAX_FP_RATE,
                String.format("False positive rate %.4f exceeds threshold %.4f", fpRate, MAX_FP_RATE));
    }

    @Test
    void emptyFilterReturnsFalseForAnyKey() {
        BloomFilter filter = new BloomFilter(100, 0.01);
        assertFalse(filter.mightContain("anything".getBytes()));
        assertFalse(filter.mightContain("".getBytes()));
    }

    @Test
    void serializeAndDeserialize() {
        BloomFilter original = new BloomFilter(1_000, 0.01);

        for (int i = 0; i < 1_000; i++) {
            original.add(("item-" + i).getBytes());
        }

        byte[] serialized = original.serialize();
        BloomFilter restored = new BloomFilter(serialized);

        // All originally-added keys must still match
        for (int i = 0; i < 1_000; i++) {
            assertTrue(restored.mightContain(("item-" + i).getBytes()),
                    "Key item-" + i + " missing after deserialization");
        }
    }

    @Test
    void serializationPreservesSize() {
        BloomFilter original = new BloomFilter(500, 0.01);
        byte[] serialized = original.serialize();
        BloomFilter restored = new BloomFilter(serialized);

        // Sanity: a key not added should still not be found
        assertFalse(restored.mightContain("never-added".getBytes()));
    }

    @Test
    void singleKeyAddedAndFound() {
        BloomFilter filter = new BloomFilter(10, 0.01);
        filter.add("hello".getBytes());

        assertTrue(filter.mightContain("hello".getBytes()));
        // "world" was never added — should probably not be present (may be FP but rare)
        // We can't guarantee false, so we just verify the added key
    }

    @Test
    void largerFpRateUsesFewerBits() {
        // A 10% FP rate filter should serialize to fewer bytes than a 1% FP rate filter
        BloomFilter loose = new BloomFilter(10_000, 0.10);
        BloomFilter strict = new BloomFilter(10_000, 0.01);

        assertTrue(loose.serialize().length < strict.serialize().length,
                "Looser FP rate should require fewer bits");
    }

    @Test
    void differentKeysDoNotInterfere() {
        BloomFilter filter = new BloomFilter(100, 0.01);

        filter.add("alpha".getBytes());
        filter.add("beta".getBytes());
        filter.add("gamma".getBytes());

        assertTrue(filter.mightContain("alpha".getBytes()));
        assertTrue(filter.mightContain("beta".getBytes()));
        assertTrue(filter.mightContain("gamma".getBytes()));
    }

    @Test
    void binaryKeySupport() {
        BloomFilter filter = new BloomFilter(100, 0.01);

        // Binary keys (not valid UTF-8)
        byte[] binaryKey = new byte[]{0x00, 0x01, (byte) 0xFF, (byte) 0xFE, 0x42};
        filter.add(binaryKey);

        assertTrue(filter.mightContain(binaryKey));
    }

    @Test
    void falsePositiveRatePrinted() {
        // Informational test — prints actual rate so we can see filter quality
        BloomFilter filter = new BloomFilter(EXPECTED_KEYS, 0.01);
        for (int i = 0; i < EXPECTED_KEYS; i++) {
            filter.add(("load-" + i).getBytes());
        }

        int fp = 0;
        for (int i = 0; i < EXPECTED_KEYS; i++) {
            if (filter.mightContain(("query-" + i).getBytes())) {
                fp++;
            }
        }

        double rate = (double) fp / EXPECTED_KEYS;
        // Log so it shows in test output — not a hard assertion beyond 2%
        System.out.printf("BloomFilter FP rate: %.4f (target ≤ 0.02)%n", rate);
        assertEquals(0, filter.mightContain("load-0".getBytes()) ? 0 : 1, // sanity
                "Added key must be found");
        assertTrue(rate < MAX_FP_RATE);
    }
}

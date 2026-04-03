package io.kiradb.core.storage.bloom;

import java.nio.ByteBuffer;

/**
 * Bloom filter — a probabilistic data structure for fast negative lookups.
 *
 * <h2>What it does</h2>
 * <p>A Bloom filter answers one question: <em>"Is this key definitely NOT in the set?"</em>
 * <ul>
 *   <li>If it says "no" → the key is <strong>definitely absent</strong>. Skip the SSTable read.</li>
 *   <li>If it says "maybe" → the key <em>might</em> be present. Read the SSTable to confirm.</li>
 * </ul>
 * There are never false negatives — if a key was added, the filter will always say "maybe".
 * There is a small configurable false-positive rate (default 1%).
 *
 * <h2>How it works</h2>
 * <p>Internally it's a bit array. When you add a key, {@code k} different hash functions
 * each set one bit. When you check a key, all {@code k} bits must be set for a "maybe".
 * If any bit is 0, the key is definitely absent.
 *
 * <h2>Double-hashing</h2>
 * <p>We use double-hashing to simulate {@code k} independent hash functions with just two:
 * <pre>
 *   h(i) = (h1(key) + i * h2(key)) % bitArraySize
 * </pre>
 * This avoids needing {@code k} separate hash implementations.
 * {@code h1} uses FNV-1a, {@code h2} uses a second-pass variation.
 *
 * <h2>Sizing</h2>
 * <p>Optimal bit array size and hash function count are computed from the expected
 * number of entries and desired false-positive rate using the standard formulas:
 * <pre>
 *   m = -n * ln(p) / (ln(2)^2)     where n = expected entries, p = FP rate
 *   k = (m / n) * ln(2)            where m = bit array size
 * </pre>
 */
public final class BloomFilter {

    private static final double LN2 = Math.log(2.0);
    private static final double LN2_SQUARED = LN2 * LN2;

    private final long[] bits;
    private final int numBits;
    private final int numHashFunctions;

    /**
     * Create a new empty Bloom filter.
     *
     * @param expectedEntries    expected number of keys to be added
     * @param falsePositiveRate  desired false-positive probability (e.g. 0.01 for 1%)
     */
    public BloomFilter(final int expectedEntries, final double falsePositiveRate) {
        this.numBits = optimalNumBits(expectedEntries, falsePositiveRate);
        this.numHashFunctions = optimalNumHashFunctions(expectedEntries, numBits);
        this.bits = new long[(numBits + 63) / 64]; // ceil to nearest long
    }

    /**
     * Deserialize a Bloom filter from the bytes written by {@link #serialize()}.
     *
     * @param data the serialized bytes
     */
    public BloomFilter(final byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        this.numBits = buf.getInt();
        this.numHashFunctions = buf.getInt();
        int longCount = buf.getInt();
        this.bits = new long[longCount];
        for (int i = 0; i < longCount; i++) {
            this.bits[i] = buf.getLong();
        }
    }

    /**
     * Add a key to the filter.
     *
     * @param key the key bytes
     */
    public void add(final byte[] key) {
        long h1 = fnv1a(key);
        long h2 = fnv1aSecond(key);
        for (int i = 0; i < numHashFunctions; i++) {
            int bitIndex = (int) (Math.abs(h1 + (long) i * h2) % numBits);
            setBit(bitIndex);
        }
    }

    /**
     * Test whether a key might be in the set.
     *
     * @param key the key to test
     * @return false if the key is definitely absent; true if it might be present
     */
    public boolean mightContain(final byte[] key) {
        long h1 = fnv1a(key);
        long h2 = fnv1aSecond(key);
        for (int i = 0; i < numHashFunctions; i++) {
            int bitIndex = (int) (Math.abs(h1 + (long) i * h2) % numBits);
            if (!getBit(bitIndex)) {
                return false; // definitely absent
            }
        }
        return true; // might be present
    }

    /**
     * Serialize this filter to bytes for embedding in an SSTable footer.
     *
     * <p>Format:
     * <pre>
     *   [4 bytes] numBits
     *   [4 bytes] numHashFunctions
     *   [4 bytes] bits array length (number of longs)
     *   [8 * N bytes] bit array
     * </pre>
     *
     * @return serialized bytes
     */
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + bits.length * Long.BYTES);
        buf.putInt(numBits);
        buf.putInt(numHashFunctions);
        buf.putInt(bits.length);
        for (long word : bits) {
            buf.putLong(word);
        }
        return buf.array();
    }

    // ── bit manipulation ──────────────────────────────────────────────────────

    private void setBit(final int index) {
        bits[index / 64] |= (1L << (index % 64));
    }

    private boolean getBit(final int index) {
        return (bits[index / 64] & (1L << (index % 64))) != 0;
    }

    // ── hash functions ────────────────────────────────────────────────────────

    /**
     * FNV-1a 64-bit hash — fast, good distribution for short keys.
     * https://en.wikipedia.org/wiki/Fowler%E2%80%93Noll%E2%80%93Vo_hash_function
     */
    private static long fnv1a(final byte[] data) {
        long hash = 0xcbf29ce484222325L; // FNV offset basis
        for (byte b : data) {
            hash ^= (b & 0xFFL);
            hash *= 0x100000001b3L;      // FNV prime
        }
        return hash;
    }

    /**
     * Second hash — same algorithm but with a different starting constant.
     * Used as h2 in the double-hashing scheme.
     */
    private static long fnv1aSecond(final byte[] data) {
        long hash = 0x84222325cbf29ceL; // reversed offset basis
        for (byte b : data) {
            hash ^= (b & 0xFFL);
            hash *= 0x00000001b3100000L; // rotated prime
        }
        return hash;
    }

    // ── sizing formulas ───────────────────────────────────────────────────────

    private static int optimalNumBits(final int n, final double p) {
        return Math.max(64, (int) Math.ceil(-n * Math.log(p) / LN2_SQUARED));
    }

    private static int optimalNumHashFunctions(final int n, final int m) {
        return Math.max(1, (int) Math.round((double) m / n * LN2));
    }

    // ── accessors for testing ─────────────────────────────────────────────────

    /**
     * Number of bits in the underlying array.
     *
     * @return bit array size
     */
    public int numBits() {
        return numBits;
    }

    /**
     * Number of hash functions used.
     *
     * @return hash function count
     */
    public int numHashFunctions() {
        return numHashFunctions;
    }
}

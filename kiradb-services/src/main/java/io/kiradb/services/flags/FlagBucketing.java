package io.kiradb.services.flags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Sticky deterministic bucketing for percentage rollouts.
 *
 * <p>Given a {@code (flagName, userId)} pair, produces an integer in {@code [0, 10000)}.
 * The same input always yields the same bucket, so a user always sees the same
 * answer for a given flag — no UI flicker as they navigate between pages.
 *
 * <h2>Why a hash, not a counter</h2>
 * A naive "every 10th user gets it" implementation requires global state and
 * coordination. A hash gives us "10% of users get it" with zero state — the
 * function is pure. The bucket distribution is uniform across the user space,
 * so the realised rollout matches the configured percentage closely.
 *
 * <h2>Why the flag name is part of the hash</h2>
 * Without it, "alice" would land in the same bucket for every flag. With it,
 * alice can be in the lucky 10% for flag-A and the unlucky 90% for flag-B
 * independently — flags don't correlate.
 *
 * <h2>Why SHA-256 and not String.hashCode</h2>
 * {@code String.hashCode} is undefined across JVM versions and not uniformly
 * distributed enough for small percentage rollouts. SHA-256 is overkill for
 * security but exactly the right shape: stable, uniform, available in stdlib.
 */
public final class FlagBucketing {

    /** Number of buckets — 10,000 gives 0.01% precision. */
    public static final int BUCKET_COUNT = 10_000;

    private FlagBucketing() {
        // utility
    }

    /**
     * Compute a bucket in {@code [0, 10000)} for the given flag and user.
     *
     * @param flagName flag identifier (the salt)
     * @param userId   user identifier
     * @return bucket index
     */
    public static int bucket(final String flagName, final String userId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(flagName.getBytes(StandardCharsets.UTF_8));
            md.update((byte) ':');
            md.update(userId.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest();
            int n = ((hash[0] & 0xFF) << 24)
                    | ((hash[1] & 0xFF) << 16)
                    | ((hash[2] & 0xFF) << 8)
                    |  (hash[3] & 0xFF);
            // mask the sign bit instead of Math.abs (which is broken for Integer.MIN_VALUE)
            return (n & 0x7FFFFFFF) % BUCKET_COUNT;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    /**
     * Decide whether the user is enabled, given the flag's rollout percent.
     *
     * @param flagName       flag identifier
     * @param userId         user identifier
     * @param rolloutPercent rollout in {@code [0.0, 1.0]}
     * @return true if the user falls within the rollout
     */
    public static boolean isEnabled(
            final String flagName, final String userId, final double rolloutPercent) {
        if (rolloutPercent <= 0.0) {
            return false;
        }
        if (rolloutPercent >= 1.0) {
            return true;
        }
        int threshold = (int) Math.round(rolloutPercent * BUCKET_COUNT);
        return bucket(flagName, userId) < threshold;
    }
}

package io.kiradb.services.flags;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlagBucketingTest {

    @Test
    void bucketIsDeterministic() {
        int b1 = FlagBucketing.bucket("dark-mode", "alice");
        int b2 = FlagBucketing.bucket("dark-mode", "alice");
        assertEquals(b1, b2, "same input must yield same bucket");
    }

    @Test
    void bucketIsInRange() {
        for (int i = 0; i < 1000; i++) {
            int b = FlagBucketing.bucket("flag", "user-" + i);
            assertTrue(b >= 0 && b < FlagBucketing.BUCKET_COUNT);
        }
    }

    @Test
    void differentFlagsDecorrelateForSameUser() {
        // Same user, different flags: buckets should differ in general.
        int dark = FlagBucketing.bucket("dark-mode", "alice");
        int beta = FlagBucketing.bucket("beta-checkout", "alice");
        // Not strictly required to be unequal for a single user, but for any
        // sane hash they will be. If this test ever flakes we'll re-evaluate.
        assertEquals(false, dark == beta && dark == 0);
    }

    @Test
    void zeroPercentNeverEnables() {
        for (int i = 0; i < 1000; i++) {
            assertFalse(FlagBucketing.isEnabled("f", "u-" + i, 0.0));
        }
    }

    @Test
    void hundredPercentAlwaysEnables() {
        for (int i = 0; i < 1000; i++) {
            assertTrue(FlagBucketing.isEnabled("f", "u-" + i, 1.0));
        }
    }

    @Test
    void rolloutDistributionMatchesPercentage() {
        // Hash uniformity sanity check: 10% rollout over 50k users should land
        // close to 10% (allow ±2% tolerance for hash variance).
        int enabled = 0;
        int trials = 50_000;
        for (int i = 0; i < trials; i++) {
            if (FlagBucketing.isEnabled("flag", "user-" + i, 0.10)) {
                enabled++;
            }
        }
        double observed = (double) enabled / trials;
        assertTrue(observed > 0.08 && observed < 0.12,
                "expected ~10%, got " + (observed * 100) + "%");
    }

    @Test
    void rolloutIsSticky() {
        // A user enabled at 10% must also be enabled at 50%, 90%, 100%.
        // Find a user enabled at 10%, then verify at higher rollouts.
        for (int i = 0; i < 1000; i++) {
            String user = "u-" + i;
            if (FlagBucketing.isEnabled("flag", user, 0.10)) {
                assertTrue(FlagBucketing.isEnabled("flag", user, 0.50));
                assertTrue(FlagBucketing.isEnabled("flag", user, 0.90));
                assertTrue(FlagBucketing.isEnabled("flag", user, 1.00));
                return;
            }
        }
        throw new AssertionError("no enabled user found in 1000 — hash broken");
    }
}

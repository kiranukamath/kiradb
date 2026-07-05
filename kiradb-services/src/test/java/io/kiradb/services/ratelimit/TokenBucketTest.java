package io.kiradb.services.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link TokenBucket}. */
final class TokenBucketTest {

    @Test
    void burstUpToCapacitySucceeds() {
        TokenBucket bucket = new TokenBucket();
        // Capacity 5, refill rate irrelevant for this immediate burst.
        for (int i = 1; i <= 5; i++) {
            RateLimitDecision dec = bucket.tryConsume("api", "user:1", 5, 1.0);
            assertTrue(dec.allowed(), "request " + i + " within burst capacity should be allowed");
        }
    }

    @Test
    void requestBeyondBurstCapacityIsThrottled() {
        TokenBucket bucket = new TokenBucket();
        for (int i = 0; i < 5; i++) {
            bucket.tryConsume("api", "user:1", 5, 1.0);
        }
        // 6th immediate request exceeds the burst capacity with a slow (1/sec) refill.
        RateLimitDecision sixth = bucket.tryConsume("api", "user:1", 5, 1.0);
        assertFalse(sixth.allowed(), "6th immediate request should be throttled");
    }

    @Test
    void refillsOverTimeAtConfiguredRate() throws InterruptedException {
        TokenBucket bucket = new TokenBucket();
        // Capacity 2, refill rate 100 tokens/sec (10ms per token) — fast enough to
        // observe refill within a short sleep without flaking on slow CI machines.
        bucket.tryConsume("api", "user:1", 2, 100.0);
        bucket.tryConsume("api", "user:1", 2, 100.0);
        RateLimitDecision immediatelyAfter = bucket.tryConsume("api", "user:1", 2, 100.0);
        assertFalse(immediatelyAfter.allowed(), "bucket should be empty immediately after burst");

        Thread.sleep(50); // 100 tokens/sec * 0.05s = ~5 tokens worth of refill, well over 1
        RateLimitDecision afterRefill = bucket.tryConsume("api", "user:1", 2, 100.0);
        assertTrue(afterRefill.allowed(), "bucket should have refilled at least one token after 50ms");
    }

    @Test
    void neverExceedsBurstCapacityEvenAfterLongIdle() throws InterruptedException {
        TokenBucket bucket = new TokenBucket();
        bucket.tryConsume("api", "user:1", 3, 1000.0); // drains to 2 tokens remaining
        Thread.sleep(50); // plenty of time to "overfill" at 1000/sec if capping were broken

        RateLimitDecision status = bucket.status("api", "user:1", 3, 1000.0);
        assertTrue(status.remaining() <= 3, "tokens must never exceed configured capacity");
    }

    @Test
    void statusDoesNotConsumeTokens() {
        TokenBucket bucket = new TokenBucket();
        bucket.tryConsume("api", "user:1", 5, 1.0);
        RateLimitDecision before = bucket.status("api", "user:1", 5, 1.0);
        RateLimitDecision after = bucket.status("api", "user:1", 5, 1.0);
        assertEquals(before.remaining(), after.remaining(),
                "status() must not itself change the remaining token count");
    }

    @Test
    void differentKeysHaveIndependentBuckets() {
        TokenBucket bucket = new TokenBucket();
        for (int i = 0; i < 5; i++) {
            bucket.tryConsume("api", "user:1", 5, 1.0);
        }
        RateLimitDecision otherUser = bucket.tryConsume("api", "user:2", 5, 1.0);
        assertTrue(otherUser.allowed(), "a different key must have its own independent bucket");
    }

    @Test
    void zeroOrNegativeCapacityAlwaysDenies() {
        TokenBucket bucket = new TokenBucket();
        assertFalse(bucket.tryConsume("api", "user:1", 0, 1.0).allowed());
        assertFalse(bucket.tryConsume("api", "user:1", -1, 1.0).allowed());
    }

    @Test
    void clearResetsAllBuckets() {
        TokenBucket bucket = new TokenBucket();
        for (int i = 0; i < 5; i++) {
            bucket.tryConsume("api", "user:1", 5, 1.0);
        }
        bucket.clear();
        RateLimitDecision afterClear = bucket.tryConsume("api", "user:1", 5, 1.0);
        assertTrue(afterClear.allowed(), "cleared bucket should start fresh at full capacity");
    }
}

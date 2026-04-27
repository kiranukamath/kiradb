package io.kiradb.services.ratelimit;

/**
 * Result of a rate-limit check.
 *
 * @param allowed         true if the request is within the limit
 * @param used            estimated count consumed in the current window
 * @param limit           the limit checked against
 * @param remaining       max(0, limit - used)
 * @param resetAtMillis   epoch-millis at which the current window rolls over
 *                        (i.e., when {@code used} drops back toward 0)
 */
public record RateLimitDecision(
        boolean allowed,
        long used,
        long limit,
        long remaining,
        long resetAtMillis) {

    /**
     * Convenience factory for a denied decision when no real counter exists yet
     * (e.g., misconfigured limit). Returns {@code allowed=false} with zero counts.
     *
     * @return a denied decision with default fields
     */
    public static RateLimitDecision denied() {
        return new RateLimitDecision(false, 0, 0, 0, 0);
    }
}

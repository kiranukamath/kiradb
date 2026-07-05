package io.kiradb.client;

import java.time.Duration;

/**
 * Outcome of a rate limiter check ({@code RL.ALLOW} / {@code RL.STATUS}).
 *
 * @param allowed    whether this request is within the limit
 * @param used       requests consumed in the current window (including this one, if allowed)
 * @param limit      the configured limit for the window
 * @param remaining  requests still available in the current window
 * @param retryAfter how long until the window rolls over and capacity frees up;
 *                   {@link Duration#ZERO} when the request was allowed
 */
public record RateLimitResult(
        boolean allowed,
        long used,
        long limit,
        long remaining,
        Duration retryAfter) {
}

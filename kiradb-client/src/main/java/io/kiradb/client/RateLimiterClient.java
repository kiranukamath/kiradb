package io.kiradb.client;

import io.kiradb.client.protocol.RespValue;

import java.time.Duration;
import java.util.Map;

/**
 * Fluent facade over the {@code RL.*} commands for one named limiter, e.g.
 * {@code db.rateLimiter("payments-api")}.
 */
public final class RateLimiterClient {

    private final KiraDB db;
    private final String limiterName;

    RateLimiterClient(final KiraDB db, final String limiterName) {
        this.db = db;
        this.limiterName = limiterName;
    }

    /**
     * Consume one unit of the limit for {@code key} and decide whether it is
     * allowed under a sliding window.
     *
     * @param key      the entity being limited, e.g. {@code "user:123"}
     * @param limit    maximum requests allowed per window
     * @param window   the sliding window duration; truncated to whole seconds
     * @return the decision, including remaining capacity and retry-after
     */
    public RateLimitResult allow(final String key, final long limit, final Duration window) {
        long periodSec = window.toSeconds();
        boolean allowed = Replies.asLong(db.call(
                "RL.ALLOW", limiterName, key, Long.toString(limit), Long.toString(periodSec))) == 1L;
        // RL.ALLOW returns only the decision bit; RL.STATUS carries full accounting.
        // One extra round trip keeps the wire format simple and RL.ALLOW cheap
        // for callers that only need the boolean.
        RateLimitResult status = status(key, limit, window);
        return new RateLimitResult(
                allowed, status.used(), status.limit(), status.remaining(), status.retryAfter());
    }

    /**
     * Inspect the current state of the window for {@code key} without
     * consuming capacity.
     *
     * @param key    the entity being limited
     * @param limit  maximum requests allowed per window
     * @param window the sliding window duration; truncated to whole seconds
     * @return the current usage snapshot
     */
    public RateLimitResult status(final String key, final long limit, final Duration window) {
        long periodSec = window.toSeconds();
        Map<String, RespValue> m = Replies.asMap(db.call(
                "RL.STATUS", limiterName, key, Long.toString(limit), Long.toString(periodSec)));
        boolean allowed = m.get("allowed") instanceof RespValue.Bool b && b.value();
        long resetAtMillis = Replies.mapLong(m, "reset_at_millis");
        long retryAfterMs = Math.max(0, resetAtMillis - System.currentTimeMillis());
        return new RateLimitResult(
                allowed,
                Replies.mapLong(m, "used"),
                Replies.mapLong(m, "limit"),
                Replies.mapLong(m, "remaining"),
                allowed ? Duration.ZERO : Duration.ofMillis(retryAfterMs));
    }
}

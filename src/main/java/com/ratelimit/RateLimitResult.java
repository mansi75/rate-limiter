package com.ratelimit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The outcome of a single rate limit decision.
 *
 * <p>Instances are immutable. The values carried are exactly those needed to
 * populate the standard rate limit response headers, so a caller can turn a
 * rejection into an HTTP 429 without consulting the limiter again.
 *
 * @param allowed    whether the permits were granted
 * @param limit      the configured ceiling for this key, for reporting purposes
 * @param remaining  permits still available after this decision; never negative
 * @param retryAfter how long until the request would succeed;
 *                   {@link Duration#ZERO} when {@code allowed} is {@code true}
 */
public record RateLimitResult(
        boolean allowed,
        long limit,
        long remaining,
        Duration retryAfter) {

    /** Header carrying the configured ceiling. */
    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    /** Header carrying the permits still available. */
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    /** Header carrying the seconds to wait before retrying. */
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if {@code retryAfter} is null
     * @throws IllegalArgumentException if {@code remaining} is negative,
     *                                  or {@code retryAfter} is negative
     */
    public RateLimitResult {
        Objects.requireNonNull(retryAfter, "retryAfter");
        if (remaining < 0) {
            throw new IllegalArgumentException("remaining must not be negative: " + remaining);
        }
        if (retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative: " + retryAfter);
        }
    }

    /**
     * Builds a successful result.
     *
     * @param limit     configured ceiling
     * @param remaining permits left after this grant
     * @return an allowed result with a zero retry delay
     */
    public static RateLimitResult allowed(long limit, long remaining) {
        return new RateLimitResult(true, limit, remaining, Duration.ZERO);
    }

    /**
     * Builds a rejected result.
     *
     * @param limit      configured ceiling
     * @param retryAfter how long the caller should wait
     * @return a rejected result with zero permits remaining
     */
    public static RateLimitResult rejected(long limit, Duration retryAfter) {
        return new RateLimitResult(false, limit, 0L, retryAfter);
    }

    /**
     * Renders this result as HTTP response headers.
     *
     * <p>{@code Retry-After} is only present on rejection, matching RFC 9110,
     * and is expressed in whole seconds rounded up so a client that waits
     * exactly that long will succeed.
     *
     * @return an ordered, mutable map of header name to value
     */
    public Map<String, String> asHeaders() {
        Map<String, String> headers = new LinkedHashMap<>(4);
        headers.put(HEADER_LIMIT, Long.toString(limit));
        headers.put(HEADER_REMAINING, Long.toString(remaining));
        if (!allowed) {
            long seconds = (retryAfter.toNanos() + 999_999_999L) / 1_000_000_000L;
            headers.put(HEADER_RETRY_AFTER, Long.toString(Math.max(seconds, 1L)));
        }
        return headers;
    }
}

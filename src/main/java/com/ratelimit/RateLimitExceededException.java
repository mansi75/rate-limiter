package com.ratelimit;

import java.util.Objects;

/**
 * Thrown by {@link RateLimiter#require(String)} when permits are unavailable.
 *
 * <p>The offending key and the full {@link RateLimitResult} are attached so an
 * exception handler can build a complete 429 response without touching the
 * limiter again.
 */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String key;
    private final transient RateLimitResult result;

    /**
     * @param key    the key that was rejected
     * @param result the rejection, including the retry delay
     */
    public RateLimitExceededException(String key, RateLimitResult result) {
        super("rate limit exceeded for key '" + key + "', retry after " + result.retryAfter());
        this.key = Objects.requireNonNull(key, "key");
        this.result = Objects.requireNonNull(result, "result");
    }

    /** @return the key that was rejected */
    public String key() {
        return key;
    }

    /** @return the full rejection result */
    public RateLimitResult result() {
        return result;
    }
}

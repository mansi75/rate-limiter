package com.ratelimit.builder;

import com.ratelimit.RateLimiter;
import com.ratelimit.algorithm.Algorithms;

import java.time.Duration;
import java.util.Objects;

/**
 * Configures a token bucket limiter.
 *
 * <pre>{@code
 * RateLimiter limiter = RateLimiters.tokenBucket()
 *         .capacity(100)
 *         .refill(10, Duration.ofSeconds(1))
 *         .build();
 * }</pre>
 *
 * <p>Capacity and refill rate are independent. Capacity sets how large a burst is
 * tolerated; the refill rate sets the long-run average. Setting capacity equal to
 * the per-second refill gives a limiter with no burst allowance at all.
 */
public final class TokenBucketBuilder extends AbstractLimiterBuilder<TokenBucketBuilder> {

    private long capacity = -1;
    private long refillPermits = -1;
    private Duration refillPeriod;
    private boolean initiallyFull = true;

    /** Use {@link com.ratelimit.RateLimiters#tokenBucket()}. */
    public TokenBucketBuilder() {
    }

    /**
     * @param capacity the largest burst permitted; must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public TokenBucketBuilder capacity(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        return this;
    }

    /**
     * @param permits permits added per {@code per}; must be positive
     * @param per     the refill period; must be positive
     * @return this builder
     * @throws IllegalArgumentException if the rate is not positive or is
     *                                  unrepresentable
     */
    public TokenBucketBuilder refill(long permits, Duration per) {
        requirePositiveRate(permits, per, "refill");
        this.refillPermits = permits;
        this.refillPeriod = per;
        return this;
    }

    /**
     * Whether a key starts with a full bucket.
     *
     * <p>Defaults to {@code true}, which is almost always right: a client that has
     * never been seen has not consumed anything. Set {@code false} to make new
     * keys warm up instead, for example when the limiter protects something that
     * cannot absorb a burst from a cold start.
     *
     * @param initiallyFull whether new keys start full
     * @return this builder
     */
    public TokenBucketBuilder initiallyFull(boolean initiallyFull) {
        this.initiallyFull = initiallyFull;
        return this;
    }

    @Override
    public RateLimiter build() {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity is required");
        }
        if (refillPeriod == null) {
            throw new IllegalArgumentException("refill is required");
        }
        requireNoOverflow(capacity, refillPeriod.toNanos() / refillPermits);
        return Algorithms.tokenBucket(
                capacity, refillPermits, refillPeriod, initiallyFull, store, timeSource, listener);
    }

    @Override
    public String toString() {
        return "TokenBucketBuilder[capacity=" + capacity
                + ", refill=" + refillPermits + " per " + refillPeriod
                + ", initiallyFull=" + initiallyFull + "]";
    }
}

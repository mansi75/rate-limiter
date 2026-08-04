package com.ratelimit.builder;

import com.ratelimit.RateLimiter;
import com.ratelimit.algorithm.Algorithms;

import java.time.Duration;

/**
 * Configures a leaky bucket limiter.
 *
 * <pre>{@code
 * RateLimiter limiter = RateLimiters.leakyBucket()
 *         .capacity(50)
 *         .leak(10, Duration.ofSeconds(1))
 *         .build();
 * }</pre>
 *
 * <p>Capacity here is queue depth, not burst allowance: how many requests may be
 * waiting before new ones are refused.
 */
public final class LeakyBucketBuilder extends AbstractLimiterBuilder<LeakyBucketBuilder> {

    private long capacity = -1;
    private long leakPermits = -1;
    private Duration leakPeriod;

    /** Use {@link com.ratelimit.RateLimiters#leakyBucket()}. */
    public LeakyBucketBuilder() {
    }

    /**
     * @param capacity how full the bucket may get; must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public LeakyBucketBuilder capacity(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        return this;
    }

    /**
     * @param permits permits drained per {@code per}; must be positive
     * @param per     the drain period; must be positive
     * @return this builder
     * @throws IllegalArgumentException if the rate is not positive or is
     *                                  unrepresentable
     */
    public LeakyBucketBuilder leak(long permits, Duration per) {
        requirePositiveRate(permits, per, "leak");
        this.leakPermits = permits;
        this.leakPeriod = per;
        return this;
    }

    @Override
    public RateLimiter build() {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity is required");
        }
        if (leakPeriod == null) {
            throw new IllegalArgumentException("leak is required");
        }
        requireNoOverflow(capacity, leakPeriod.toNanos() / leakPermits);
        return Algorithms.leakyBucket(
                capacity, leakPermits, leakPeriod, store, timeSource, listener);
    }
}

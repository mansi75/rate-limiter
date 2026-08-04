package com.ratelimit.builder;

import com.ratelimit.RateLimiter;
import com.ratelimit.algorithm.Algorithms;

import java.time.Duration;

/**
 * Configures a GCRA limiter.
 *
 * <pre>{@code
 * RateLimiter limiter = RateLimiters.gcra()
 *         .rate(100, Duration.ofMinutes(1))
 *         .burst(10)
 *         .build();
 * }</pre>
 *
 * <p>Rate and burst are separate concerns: the rate is what the caller may sustain
 * indefinitely, the burst is how far ahead of that they may run momentarily. A
 * burst of 1 permits no bursting at all and produces perfectly even spacing.
 */
public final class GcraBuilder extends AbstractLimiterBuilder<GcraBuilder> {

    private long ratePermits = -1;
    private Duration ratePeriod;
    private long burst = 1;

    /** Use {@link com.ratelimit.RateLimiters#gcra()}. */
    public GcraBuilder() {
    }

    /**
     * @param permits permits sustainable per {@code per}; must be positive
     * @param per     the period; must be positive
     * @return this builder
     * @throws IllegalArgumentException if the rate is not positive or is
     *                                  unrepresentable
     */
    public GcraBuilder rate(long permits, Duration per) {
        requirePositiveRate(permits, per, "rate");
        this.ratePermits = permits;
        this.ratePeriod = per;
        return this;
    }

    /**
     * @param burst how far ahead of the sustained rate a caller may run;
     *              must be positive. Defaults to 1, meaning no bursting.
     * @return this builder
     * @throws IllegalArgumentException if {@code burst} is not positive
     */
    public GcraBuilder burst(long burst) {
        if (burst <= 0) {
            throw new IllegalArgumentException("burst must be positive: " + burst);
        }
        this.burst = burst;
        return this;
    }

    @Override
    public RateLimiter build() {
        if (ratePeriod == null) {
            throw new IllegalArgumentException("rate is required");
        }
        requireNoOverflow(burst, ratePeriod.toNanos() / ratePermits);
        return Algorithms.gcra(ratePermits, ratePeriod, burst, store, timeSource, listener);
    }
}

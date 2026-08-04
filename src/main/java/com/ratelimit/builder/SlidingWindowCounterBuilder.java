package com.ratelimit.builder;

import com.ratelimit.RateLimiter;
import com.ratelimit.algorithm.Algorithms;

/**
 * Configures a sliding window counter limiter.
 *
 * <pre>{@code
 * RateLimiter limiter = RateLimiters.slidingWindowCounter()
 *         .limit(1000)
 *         .window(Duration.ofMinutes(1))
 *         .build();
 * }</pre>
 *
 * <p>Constant memory per key and no boundary burst. The best default of the three
 * window algorithms.
 */
public final class SlidingWindowCounterBuilder
        extends AbstractWindowBuilder<SlidingWindowCounterBuilder> {

    /** Use {@link com.ratelimit.RateLimiters#slidingWindowCounter()}. */
    public SlidingWindowCounterBuilder() {
    }

    @Override
    public RateLimiter build() {
        requireComplete();
        return Algorithms.slidingWindowCounter(limit, window, store, timeSource, listener);
    }
}

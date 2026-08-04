package com.ratelimit.builder;

import com.ratelimit.RateLimiter;
import com.ratelimit.algorithm.Algorithms;

/**
 * Configures a sliding window log limiter.
 *
 * <pre>{@code
 * RateLimiter limiter = RateLimiters.slidingWindowLog()
 *         .limit(10)
 *         .window(Duration.ofMinutes(1))
 *         .build();
 * }</pre>
 *
 * <p>Exact, at a memory cost proportional to the limit. Prefer it for small
 * limits where precision matters; prefer {@link SlidingWindowCounterBuilder} for
 * large ones.
 */
public final class SlidingWindowLogBuilder extends AbstractWindowBuilder<SlidingWindowLogBuilder> {

    /** Use {@link com.ratelimit.RateLimiters#slidingWindowLog()}. */
    public SlidingWindowLogBuilder() {
    }

    @Override
    public RateLimiter build() {
        requireComplete();
        return Algorithms.slidingWindowLog(limit, window, store, timeSource, listener);
    }
}

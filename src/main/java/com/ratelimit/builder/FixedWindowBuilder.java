package com.ratelimit.builder;

import com.ratelimit.RateLimiter;
import com.ratelimit.algorithm.Algorithms;

/**
 * Configures a fixed window limiter.
 *
 * <pre>{@code
 * RateLimiter limiter = RateLimiters.fixedWindow()
 *         .limit(100)
 *         .window(Duration.ofMinutes(1))
 *         .build();
 * }</pre>
 *
 * <p><strong>Read the algorithm's documentation before choosing it.</strong> A
 * fixed window permits twice the configured limit across a window boundary. Use
 * {@link SlidingWindowCounterBuilder} unless you specifically want this one.
 */
public final class FixedWindowBuilder extends AbstractWindowBuilder<FixedWindowBuilder> {

    /** Use {@link com.ratelimit.RateLimiters#fixedWindow()}. */
    public FixedWindowBuilder() {
    }

    @Override
    public RateLimiter build() {
        requireComplete();
        return Algorithms.fixedWindow(limit, window, store, timeSource, listener);
    }
}

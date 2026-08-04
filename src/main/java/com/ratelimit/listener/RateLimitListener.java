package com.ratelimit.listener;

import com.ratelimit.RateLimitResult;

/**
 * Observes rate limit decisions.
 *
 * <p>Exists so that this library can be instrumented without depending on any
 * particular metrics framework. Implement it against Micrometer, Dropwizard, a
 * logger, or a counter you increment yourself.
 *
 * <p>Every method has a default no-op body, so implementers override only what
 * they care about.
 *
 * <p>Callbacks run on the calling thread, inline with the decision, and after the
 * store lock has been released. A slow listener slows down the caller; do the
 * expensive part elsewhere.
 */
public interface RateLimitListener {

    /** A listener that does nothing. */
    RateLimitListener NOOP = new RateLimitListener() {
    };

    /**
     * Called after permits were granted.
     *
     * @param key    the key
     * @param result the granting result
     */
    default void onAllowed(String key, RateLimitResult result) {
    }

    /**
     * Called after permits were refused.
     *
     * @param key    the key
     * @param result the refusing result, carrying the retry delay
     */
    default void onRejected(String key, RateLimitResult result) {
    }

    /**
     * Called after a key's state was discarded.
     *
     * @param key the key
     */
    default void onReset(String key) {
    }
}

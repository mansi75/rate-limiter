package com.ratelimit;

import java.time.Duration;

/**
 * Decides whether an action identified by a key may proceed.
 *
 * <p>This is the only type callers need to depend on. The choice of algorithm
 * is a configuration concern, made once at construction time through
 * {@link RateLimiters}, and is deliberately not visible in the type system.
 *
 * <pre>{@code
 * RateLimiter limiter = RateLimiters.tokenBucket()
 *         .capacity(100)
 *         .refill(10, Duration.ofSeconds(1))
 *         .build();
 *
 * if (limiter.isAllowed("user:42")) {
 *     handle(request);
 * }
 * }</pre>
 *
 * <p>The key is opaque to the limiter. Callers typically namespace it by
 * dimension, for example {@code "ip:203.0.113.9"} or {@code "user:42:search"}.
 * State is created lazily on first use of a key.
 *
 * <p>All implementations in this library are safe for concurrent use by
 * multiple threads.
 */
public interface RateLimiter {

    /**
     * Attempts to take a single permit. Never blocks.
     *
     * @param key the subject of the limit
     * @return the decision
     * @throws NullPointerException if {@code key} is null
     */
    default RateLimitResult tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    /**
     * Attempts to take {@code permits} permits, all or nothing.
     *
     * <p>A request for more permits than the limiter's capacity can never
     * succeed and is rejected immediately rather than waiting forever.
     *
     * @param key     the subject of the limit
     * @param permits how many permits to take; must be positive
     * @return the decision
     * @throws NullPointerException     if {@code key} is null
     * @throws IllegalArgumentException if {@code permits} is not positive
     */
    RateLimitResult tryAcquire(String key, int permits);

    /**
     * Reports what {@link #tryAcquire(String, int)} would return, without
     * consuming anything.
     *
     * <p>This exists so that a limiter composed of several delegates can check
     * every delegate before committing to any of them. Without it, a composite
     * would consume a permit from the first delegate and then discover the
     * second rejects, leaking the permit.
     *
     * <p>The result is a snapshot. Under concurrency it may be stale by the time
     * the caller acts on it.
     *
     * @param key     the subject of the limit
     * @param permits how many permits to test for; must be positive
     * @return the hypothetical decision
     * @throws NullPointerException     if {@code key} is null
     * @throws IllegalArgumentException if {@code permits} is not positive
     */
    RateLimitResult peek(String key, int permits);

    /**
     * Waits up to {@code timeout} for permits to become available.
     *
     * <p>Implementations sleep for the retry delay reported by the underlying
     * algorithm rather than spinning, and re-check on wake.
     *
     * @param key     the subject of the limit
     * @param permits how many permits to take; must be positive
     * @param timeout how long to wait; must not be negative
     * @return the decision; rejected if the timeout elapsed first
     * @throws InterruptedException     if the calling thread is interrupted
     * @throws NullPointerException     if {@code key} or {@code timeout} is null
     * @throws IllegalArgumentException if {@code permits} is not positive
     *                                  or {@code timeout} is negative
     */
    RateLimitResult acquire(String key, int permits, Duration timeout) throws InterruptedException;

    /**
     * Discards all state for a key, as if it had never been seen.
     *
     * @param key the key to forget
     * @throws NullPointerException if {@code key} is null
     */
    void reset(String key);

    /**
     * Convenience form of {@link #tryAcquire(String)} for callers that only care
     * whether the action may proceed.
     *
     * @param key the subject of the limit
     * @return {@code true} if a permit was taken
     */
    default boolean isAllowed(String key) {
        return tryAcquire(key).allowed();
    }

    /**
     * Takes a permit or throws, for use at a service boundary where rejection is
     * exceptional.
     *
     * @param key the subject of the limit
     * @throws RateLimitExceededException if no permit was available
     */
    default void require(String key) {
        RateLimitResult result = tryAcquire(key);
        if (!result.allowed()) {
            throw new RateLimitExceededException(key, result);
        }
    }
}

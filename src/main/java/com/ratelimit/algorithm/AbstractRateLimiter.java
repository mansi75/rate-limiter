package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.RateLimiter;
import com.ratelimit.listener.RateLimitListener;
import com.ratelimit.store.LimiterStore;
import com.ratelimit.time.TimeSource;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Shared plumbing for the single-algorithm limiters: argument validation,
 * listener dispatch, and the blocking {@link #acquire} loop.
 *
 * <p>Subclasses supply only the algorithm. Everything a reviewer would otherwise
 * see repeated six times lives here once.
 */
abstract class AbstractRateLimiter implements RateLimiter {

    /** Where per-key state lives. */
    protected final LimiterStore store;
    /** Monotonic time. */
    protected final TimeSource timeSource;
    /** Notified of every decision. */
    protected final RateLimitListener listener;

    AbstractRateLimiter(LimiterStore store, TimeSource timeSource, RateLimitListener listener) {
        this.store = Objects.requireNonNull(store, "store");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Runs the algorithm and consumes permits if it grants them.
     *
     * @param key      validated key
     * @param permits  validated positive permit count
     * @param nowNanos current time
     * @return the decision
     */
    protected abstract RateLimitResult doAcquire(String key, int permits, long nowNanos);

    /**
     * Runs the algorithm without consuming anything.
     *
     * @param key      validated key
     * @param permits  validated positive permit count
     * @param nowNanos current time
     * @return the hypothetical decision
     */
    protected abstract RateLimitResult doPeek(String key, int permits, long nowNanos);

    /**
     * @return the largest permit count this limiter could ever grant
     */
    protected abstract long capacity();

    @Override
    public final RateLimitResult tryAcquire(String key, int permits) {
        validate(key, permits);
        if (permits > capacity()) {
            // Unsatisfiable for any amount of waiting. Fail fast and say so,
            // rather than reporting a retry delay that will never come true.
            RateLimitResult result = RateLimitResult.rejected(capacity(), Duration.ZERO);
            listener.onRejected(key, result);
            return result;
        }
        RateLimitResult result = doAcquire(key, permits, timeSource.nanoTime());
        if (result.allowed()) {
            listener.onAllowed(key, result);
        } else {
            listener.onRejected(key, result);
        }
        return result;
    }

    @Override
    public final RateLimitResult peek(String key, int permits) {
        validate(key, permits);
        if (permits > capacity()) {
            return RateLimitResult.rejected(capacity(), Duration.ZERO);
        }
        return doPeek(key, permits, timeSource.nanoTime());
    }

    @Override
    public final RateLimitResult acquire(String key, int permits, Duration timeout)
            throws InterruptedException {

        validate(key, permits);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }

        long deadlineNanos = timeSource.nanoTime() + timeout.toNanos();

        while (true) {
            RateLimitResult result = tryAcquire(key, permits);
            if (result.allowed()) {
                return result;
            }
            long remainingNanos = deadlineNanos - timeSource.nanoTime();
            if (remainingNanos <= 0) {
                return result;
            }
            // Sleep for exactly as long as the algorithm says is needed, capped
            // by the caller's deadline. Sleeping rather than spinning keeps a
            // saturated limiter from burning a core per waiting thread.
            long sleepNanos = Math.min(result.retryAfter().toNanos(), remainingNanos);
            if (sleepNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(sleepNanos);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    @Override
    public final void reset(String key) {
        Objects.requireNonNull(key, "key");
        store.reset(key);
        listener.onReset(key);
    }

    private static void validate(String key, int permits) {
        Objects.requireNonNull(key, "key");
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive: " + permits);
        }
    }
}

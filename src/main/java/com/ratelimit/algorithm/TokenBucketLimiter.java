package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.listener.RateLimitListener;
import com.ratelimit.state.BucketState;
import com.ratelimit.store.LimiterStore;
import com.ratelimit.store.StateMutation;
import com.ratelimit.time.TimeSource;

import java.time.Duration;
import java.util.function.LongFunction;

/**
 * Token bucket: permits accumulate at a fixed rate up to a ceiling, and each
 * request drains some.
 *
 * <p>Allows bursts up to the bucket capacity while holding the long-run average
 * at the refill rate. This is the algorithm most people mean when they say
 * "rate limit", and the right default unless bursts are actively harmful.
 *
 * <h2>Lazy refill</h2>
 *
 * <p>No background thread adds tokens. The bucket is topped up on read, from the
 * time elapsed since the last decision. A thread per limiter would not scale past
 * a handful of limiters, and a shared scheduler would make the refill interval a
 * source of jitter. Computing from elapsed time is both cheaper and more precise.
 *
 * <h2>Why permits are counted in nanoseconds</h2>
 *
 * <p>See {@link BucketState}. Tokens are held as banked refill time, so the
 * arithmetic stays in {@code long} and slow rates do not drift.
 *
 * <p>This class is thread-safe; all mutation happens inside the store.
 */
final class TokenBucketLimiter extends AbstractRateLimiter {

    private final long capacity;
    private final long nanosPerPermit;
    private final long capacityNanos;
    private final boolean initiallyFull;

    // Bound method references, allocated once. See LimiterStore's note on why
    // the mutation is not a capturing lambda.
    private final StateMutation<BucketState> acquireMutation = this::acquireMutation;
    private final StateMutation<BucketState> peekMutation = this::peekMutation;
    private final LongFunction<BucketState> stateFactory = this::newState;

    /**
     * @param capacity       maximum permits held at once, i.e. the burst size
     * @param refillPermits  permits added per {@code refillPeriod}
     * @param refillPeriod   the period over which {@code refillPermits} accrue
     * @param initiallyFull  whether a new key starts with a full bucket
     */
    TokenBucketLimiter(
            long capacity,
            long refillPermits,
            Duration refillPeriod,
            boolean initiallyFull,
            LimiterStore store,
            TimeSource timeSource,
            RateLimitListener listener) {

        super(store, timeSource, listener);
        this.capacity = capacity;
        this.nanosPerPermit = refillPeriod.toNanos() / refillPermits;
        this.capacityNanos = capacity * nanosPerPermit;
        this.initiallyFull = initiallyFull;
    }

    @Override
    protected long capacity() {
        return capacity;
    }

    @Override
    protected RateLimitResult doAcquire(String key, int permits, long nowNanos) {
        return store.compute(key, stateFactory, acquireMutation, nowNanos, permits);
    }

    @Override
    protected RateLimitResult doPeek(String key, int permits, long nowNanos) {
        return store.compute(key, stateFactory, peekMutation, nowNanos, permits);
    }

    private BucketState newState(long nowNanos) {
        return new BucketState(initiallyFull ? capacityNanos : 0L, nowNanos);
    }

    /**
     * The algorithm. Runs with exclusive access to {@code state}.
     */
    private RateLimitResult acquireMutation(BucketState state, long nowNanos, int permits) {
        refill(state, nowNanos);
        long costNanos = permits * nanosPerPermit;

        if (state.availableNanos >= costNanos) {
            state.availableNanos -= costNanos;
            return RateLimitResult.allowed(capacity, state.availableNanos / nanosPerPermit);
        }
        long shortfallNanos = costNanos - state.availableNanos;
        return RateLimitResult.rejected(capacity, Duration.ofNanos(shortfallNanos));
    }

    /**
     * The same decision, computed without writing anything back. The refill is
     * done into local variables so the state is left exactly as it was found.
     */
    private RateLimitResult peekMutation(BucketState state, long nowNanos, int permits) {
        long available = projectedAvailableNanos(state, nowNanos);
        long costNanos = permits * nanosPerPermit;

        if (available >= costNanos) {
            return RateLimitResult.allowed(capacity, (available - costNanos) / nanosPerPermit);
        }
        return RateLimitResult.rejected(capacity, Duration.ofNanos(costNanos - available));
    }

    /** Tops the bucket up from elapsed time, saturating at capacity. */
    private void refill(BucketState state, long nowNanos) {
        state.availableNanos = projectedAvailableNanos(state, nowNanos);
        state.lastRefillNanos = nowNanos;
    }

    private long projectedAvailableNanos(BucketState state, long nowNanos) {
        long elapsed = nowNanos - state.lastRefillNanos;
        if (elapsed <= 0) {
            return state.availableNanos;
        }
        // Guard against overflow on a long-idle key before clamping.
        long refilled = (elapsed > capacityNanos)
                ? capacityNanos
                : state.availableNanos + elapsed;
        return Math.min(refilled, capacityNanos);
    }
}

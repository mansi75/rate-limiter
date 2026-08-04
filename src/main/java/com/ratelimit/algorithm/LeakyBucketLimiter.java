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
 * Leaky bucket: requests fill a bucket that drains at a constant rate, and
 * anything that would overflow is refused.
 *
 * <h2>How this differs from the token bucket</h2>
 *
 * <p>The arithmetic is nearly identical; the intent is opposite. A token bucket
 * banks unused capacity so an idle client may burst. A leaky bucket does not: it
 * smooths output to a steady rate no matter how the input arrives.
 *
 * <p>Use it when the thing being protected cares about instantaneous rate rather
 * than average rate. A downstream API with a hard concurrency limit, a hardware
 * device, an outbound SMS gateway billed per second. Use a token bucket when the
 * limit exists to be fair over time and bursts are harmless.
 *
 * <h2>Implementation notes</h2>
 *
 * <p>Reuses {@link BucketState}, but the meaning of the field inverts:
 * {@code availableNanos} tracks how full the bucket is rather than how much
 * credit is banked. Consider renaming the field or introducing a dedicated state
 * type if that inversion reads as confusing; a shared type that means two things
 * is worse than two small types.
 *
 * <p>The mutation is:
 * <ol>
 *   <li>Drain: subtract elapsed nanos from the fill level, flooring at zero.</li>
 *   <li>If {@code fillNanos + permits * nanosPerPermit <= capacityNanos}, add and
 *       allow.</li>
 *   <li>Otherwise reject, with a retry delay of the time needed to drain enough
 *       room.</li>
 * </ol>
 */
final class LeakyBucketLimiter extends AbstractRateLimiter {

    private final long capacity;
    private final long nanosPerPermit;
    private final long capacityNanos;

    private final StateMutation<BucketState> acquireMutation = this::acquireMutation;
    private final StateMutation<BucketState> peekMutation = this::peekMutation;
    private final LongFunction<BucketState> stateFactory = now -> new BucketState(0L, now);

    LeakyBucketLimiter(
            long capacity,
            long leakPermits,
            Duration leakPeriod,
            LimiterStore store,
            TimeSource timeSource,
            RateLimitListener listener) {

        super(store, timeSource, listener);
        this.capacity = capacity;
        this.nanosPerPermit = leakPeriod.toNanos() / leakPermits;
        this.capacityNanos = capacity * nanosPerPermit;
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

    private RateLimitResult acquireMutation(BucketState state, long nowNanos, int permits) {
        throw new UnsupportedOperationException("TODO: see the class JavaDoc for the algorithm");
    }

    private RateLimitResult peekMutation(BucketState state, long nowNanos, int permits) {
        throw new UnsupportedOperationException("TODO: see the class JavaDoc for the algorithm");
    }
}

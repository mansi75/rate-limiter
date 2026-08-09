package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.listener.RateLimitListener;
import com.ratelimit.state.QueueState;
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
 * <p>State is {@link QueueState}, not the token bucket's {@code BucketState}, even
 * though the two hold the same shape. The sign is inverted — {@code fillNanos} is
 * backlog owed rather than credit banked — and one field meaning both reads as a
 * bug at every call site.
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

    private final StateMutation<QueueState> acquireMutation = this::acquireMutation;
    private final StateMutation<QueueState> peekMutation = this::peekMutation;
    private final LongFunction<QueueState> stateFactory = QueueState::new;

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

    private RateLimitResult acquireMutation(QueueState state, long nowNanos, int permits) {
        drain(state, nowNanos);

        long costNanos = permits * nanosPerPermit;
        long headroomNanos = capacityNanos - state.fillNanos;

        if (costNanos <= headroomNanos) {
            state.fillNanos += costNanos;
            return RateLimitResult.allowed(capacity, (headroomNanos - costNanos) / nanosPerPermit);
        }
        // The bucket drains a nanosecond of fill per nanosecond of real time, so the
        // shortfall in room is itself the wait.
        return RateLimitResult.rejected(capacity, Duration.ofNanos(costNanos - headroomNanos));
    }

    /**
     * The same decision, computed into local variables so the state is left exactly
     * as it was found.
     */
    private RateLimitResult peekMutation(QueueState state, long nowNanos, int permits) {
        long fillNanos = projectedFillNanos(state, nowNanos);

        long costNanos = permits * nanosPerPermit;
        long headroomNanos = capacityNanos - fillNanos;

        if (costNanos <= headroomNanos) {
            return RateLimitResult.allowed(capacity, (headroomNanos - costNanos) / nanosPerPermit);
        }
        return RateLimitResult.rejected(capacity, Duration.ofNanos(costNanos - headroomNanos));
    }

    /** Lets the bucket leak for the time that has passed, then marks the clock. */
    private void drain(QueueState state, long nowNanos) {
        state.fillNanos = projectedFillNanos(state, nowNanos);
        state.lastDrainNanos = nowNanos;
    }

    /**
     * How full the bucket is now, without touching the state.
     *
     * <p>Draining is the mirror of the token bucket's refill: one nanosecond of fill
     * leaves per nanosecond of real time, so the leak rate never appears in the
     * arithmetic. It is carried entirely by {@code nanosPerPermit}, the cost of
     * putting one request in.
     */
    private long projectedFillNanos(QueueState state, long nowNanos) {
        long elapsed = nowNanos - state.lastDrainNanos;
        if (elapsed <= 0) {
            return state.fillNanos;
        }
        // Compared rather than subtracted-then-clamped: the bucket cannot leak past
        // empty, and the comparison also keeps a long-idle key from underflowing.
        return (elapsed >= state.fillNanos) ? 0L : state.fillNanos - elapsed;
    }
}

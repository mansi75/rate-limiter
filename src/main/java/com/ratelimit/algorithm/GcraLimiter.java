package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.listener.RateLimitListener;
import com.ratelimit.state.GcraState;
import com.ratelimit.store.LimiterStore;
import com.ratelimit.store.StateMutation;
import com.ratelimit.time.TimeSource;

import java.time.Duration;
import java.util.function.LongFunction;

/**
 * Generic cell rate algorithm: a token bucket expressed as a single timestamp.
 *
 * <p>Originally specified for ATM traffic shaping, and now what most production
 * rate limiters actually run, including the widely used Redis implementations.
 * It is behaviourally equivalent to a token bucket but stores one {@code long}
 * per key instead of two, and needs no clamping logic.
 *
 * <h2>The idea</h2>
 *
 * <p>Track the theoretical arrival time (TAT) of the next permit that would
 * exactly conform to the configured rate. Two constants:
 *
 * <ul>
 *   <li>{@code emissionInterval} — nanos per permit at the sustained rate.</li>
 *   <li>{@code delayTolerance} — {@code burst * emissionInterval}: how far ahead
 *       of the TAT a caller is allowed to run.</li>
 * </ul>
 *
 * <p>The decision, for {@code permits} permits:
 *
 * <pre>{@code
 * long increment = permits * emissionInterval;
 * long tat       = Math.max(state.tatNanos, nowNanos);
 * long allowAt   = tat + increment - delayTolerance;
 *
 * if (allowAt <= nowNanos) {
 *     state.tatNanos = tat + increment;   // conforming
 *     return allowed(...);
 * }
 * return rejected(..., Duration.ofNanos(allowAt - nowNanos));
 * }</pre>
 *
 * <p>That is the whole algorithm. No refill loop, no window roll, no pruning,
 * and the retry delay falls out exactly rather than being estimated.
 *
 * <p>Remaining permits, for reporting, are
 * {@code (nowNanos - (tat - delayTolerance)) / emissionInterval}, floored at zero.
 *
 * <p>{@code peek} runs the same computation without assigning {@code tatNanos}.
 *
 * <p>Worth implementing last, and worth understanding well: it is the algorithm a
 * reviewer is most likely to ask about, because it is the one they are least
 * likely to have written themselves.
 */
final class GcraLimiter extends AbstractRateLimiter {

    private final long limit;
    private final long emissionIntervalNanos;
    private final long delayToleranceNanos;

    private final StateMutation<GcraState> acquireMutation = this::acquireMutation;
    private final StateMutation<GcraState> peekMutation = this::peekMutation;
    private final LongFunction<GcraState> stateFactory = GcraState::new;

    GcraLimiter(
            long ratePermits,
            Duration ratePeriod,
            long burst,
            LimiterStore store,
            TimeSource timeSource,
            RateLimitListener listener) {

        super(store, timeSource, listener);
        this.limit = burst;
        this.emissionIntervalNanos = ratePeriod.toNanos() / ratePermits;
        this.delayToleranceNanos = burst * emissionIntervalNanos;
    }

    @Override
    protected long capacity() {
        return limit;
    }

    @Override
    protected RateLimitResult doAcquire(String key, int permits, long nowNanos) {
        return store.compute(key, stateFactory, acquireMutation, nowNanos, permits);
    }

    @Override
    protected RateLimitResult doPeek(String key, int permits, long nowNanos) {
        return store.compute(key, stateFactory, peekMutation, nowNanos, permits);
    }

    private RateLimitResult acquireMutation(GcraState state, long nowNanos, int permits) {
        return decide(state, nowNanos, permits, true);
    }

    private RateLimitResult peekMutation(GcraState state, long nowNanos, int permits) {
        return decide(state, nowNanos, permits, false);
    }

    /**
     * The algorithm. Acquire and peek differ by a single assignment, so they share
     * the arithmetic rather than restating a formula that is easy to get subtly
     * wrong in one copy and not the other.
     *
     * @param commit whether a conforming request should advance the arrival time
     */
    private RateLimitResult decide(GcraState state, long nowNanos, int permits, boolean commit) {
        long incrementNanos = permits * emissionIntervalNanos;
        // A caller that has been idle is caught up, never ahead: clamping to now is
        // what stops unused time accumulating into an unbounded burst.
        long arrivalNanos = Math.max(state.tatNanos, nowNanos);
        long updatedArrivalNanos = arrivalNanos + incrementNanos;
        long allowAtNanos = updatedArrivalNanos - delayToleranceNanos;

        if (allowAtNanos > nowNanos) {
            // Too far ahead of schedule. The gap is exactly the wait, with no estimate.
            return RateLimitResult.rejected(limit, Duration.ofNanos(allowAtNanos - nowNanos));
        }
        if (commit) {
            state.tatNanos = updatedArrivalNanos;
        }
        return RateLimitResult.allowed(limit, remainingAt(updatedArrivalNanos, nowNanos));
    }

    /**
     * How many further permits the tolerance would still cover, derived from the
     * arrival time rather than counted.
     */
    private long remainingAt(long arrivalNanos, long nowNanos) {
        long slackNanos = nowNanos - (arrivalNanos - delayToleranceNanos);
        if (slackNanos <= 0) {
            return 0L;
        }
        return Math.min(limit, slackNanos / emissionIntervalNanos);
    }
}

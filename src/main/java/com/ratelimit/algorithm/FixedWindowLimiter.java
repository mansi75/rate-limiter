package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.listener.RateLimitListener;
import com.ratelimit.state.WindowState;
import com.ratelimit.store.LimiterStore;
import com.ratelimit.store.StateMutation;
import com.ratelimit.time.TimeSource;

import java.time.Duration;
import java.util.function.LongFunction;

/**
 * Fixed window counter: a counter that resets on a wall-clock boundary.
 *
 * <h2>This algorithm is wrong, and it ships on purpose</h2>
 *
 * <p>It is the most commonly implemented rate limiter and it does not do what
 * people think it does. A limit of 100 per minute permits 200 requests in a two
 * second span: 100 at 11:00:59, the counter resets, 100 more at 11:01:00. The
 * long-run average is respected; the burst the limit was meant to prevent is not.
 *
 * <p>It is included so the library can demonstrate the failure rather than assert
 * it. {@code FixedWindowBoundaryTest} drives exactly that scenario and asserts
 * 200 requests succeed, then runs the identical scenario through the sliding
 * window counter and asserts the second hundred is refused.
 *
 * <p>Choose it only when its cheapness genuinely outweighs its inaccuracy: one
 * counter and one timestamp per key, no pruning, no arithmetic.
 *
 * <h2>Implementation notes</h2>
 *
 * <p>The mutation is:
 * <ol>
 *   <li>If {@code nowNanos - state.currentWindowStartNanos >= windowNanos}, the
 *       window has rolled: set {@code previousCount = currentCount},
 *       {@code currentCount = 0}, and advance {@code currentWindowStartNanos}.
 *       Advance it by whole multiples of {@code windowNanos} rather than setting
 *       it to {@code nowNanos}, or windows will slowly drift with call timing.</li>
 *   <li>If {@code currentCount + permits <= limit}, add and allow.</li>
 *   <li>Otherwise reject, with a retry delay of the time remaining until the
 *       window rolls.</li>
 * </ol>
 *
 * <p>{@code peek} performs the same computation into local variables and writes
 * nothing back.
 */
final class FixedWindowLimiter extends AbstractRateLimiter {

    private final long limit;
    private final long windowNanos;

    private final StateMutation<WindowState> acquireMutation = this::acquireMutation;
    private final StateMutation<WindowState> peekMutation = this::peekMutation;
    private final LongFunction<WindowState> stateFactory = WindowState::new;

    FixedWindowLimiter(
            long limit,
            Duration window,
            LimiterStore store,
            TimeSource timeSource,
            RateLimitListener listener) {

        super(store, timeSource, listener);
        this.limit = limit;
        this.windowNanos = window.toNanos();
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

    private RateLimitResult acquireMutation(WindowState state, long nowNanos, int permits) {
        roll(state, nowNanos);

        if (state.currentCount + permits <= limit) {
            state.currentCount += permits;
            return RateLimitResult.allowed(limit, limit - state.currentCount);
        }
        return RateLimitResult.rejected(limit,
                Duration.ofNanos(nanosUntilRoll(state.currentWindowStartNanos, nowNanos)));
    }

    /**
     * The same decision, computed into local variables so the state is left exactly
     * as it was found.
     */
    private RateLimitResult peekMutation(WindowState state, long nowNanos, int permits) {
        long windowStart = projectedWindowStart(state, nowNanos);
        // A rolled window would have zeroed the counter; anything else keeps it.
        long count = (windowStart == state.currentWindowStartNanos) ? state.currentCount : 0L;

        if (count + permits <= limit) {
            return RateLimitResult.allowed(limit, limit - count - permits);
        }
        return RateLimitResult.rejected(limit,
                Duration.ofNanos(nanosUntilRoll(windowStart, nowNanos)));
    }

    /** Zeroes the counter if the clock has crossed into a later window. */
    private void roll(WindowState state, long nowNanos) {
        long newStart = projectedWindowStart(state, nowNanos);
        if (newStart == state.currentWindowStartNanos) {
            return;
        }
        long windowsPassed = (newStart - state.currentWindowStartNanos) / windowNanos;
        // Only the window immediately behind us has a count worth carrying; if two or
        // more elapsed, the previous one saw no traffic at all. Unused here, but the
        // sliding window counter shares this state and blends the two.
        state.previousCount = (windowsPassed == 1) ? state.currentCount : 0L;
        state.currentCount = 0L;
        state.currentWindowStartNanos = newStart;
    }

    /**
     * Where the current window starts, given the time, without touching the state.
     *
     * <p>Advances by whole multiples of the window rather than snapping to
     * {@code nowNanos}: anchoring to call timing would stretch each window by
     * however late its first caller happened to be, and the boundaries would drift.
     */
    private long projectedWindowStart(WindowState state, long nowNanos) {
        long elapsed = nowNanos - state.currentWindowStartNanos;
        if (elapsed < windowNanos) {
            return state.currentWindowStartNanos;
        }
        return state.currentWindowStartNanos + (elapsed / windowNanos) * windowNanos;
    }

    /**
     * Time left in the window starting at {@code windowStartNanos}. Computed as a
     * remainder rather than as {@code start + windowNanos - now}, so a window start
     * near the top of the {@code long} range cannot overflow the addition.
     *
     * <p>Takes the start as a parameter rather than reading it from the state, so
     * that {@code peek} can pass a projected start it has not written back.
     */
    private long nanosUntilRoll(long windowStartNanos, long nowNanos) {
        return windowNanos - (nowNanos - windowStartNanos);
    }
}

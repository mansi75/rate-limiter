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
 * Sliding window counter: a fixed window, corrected by a weighted share of the
 * window before it.
 *
 * <p>Fixes the boundary burst of {@link FixedWindowLimiter} for the price of two
 * counters instead of one, and without the unbounded memory of
 * {@link SlidingWindowLogLimiter}. For most callers this is the right choice.
 *
 * <h2>The estimate</h2>
 *
 * <p>Let {@code elapsed} be how far into the current window we are, and
 * {@code w = elapsed / windowNanos} the fraction of it consumed. The estimated
 * count over the trailing window is:
 *
 * <pre>{@code
 * estimate = previousCount * (1 - w) + currentCount
 * }</pre>
 *
 * <p>Worked example, limit 100, window 60s. At t=75s we are 15s (w = 0.25) into
 * the second window. The previous window recorded 100, the current 20. The
 * estimate is {@code 100 * 0.75 + 20 = 95}, so five permits remain. The fixed
 * window would have reported 20 used and 80 remaining, letting a burst through.
 *
 * <p>The estimate assumes the previous window's requests were spread evenly
 * across it. They may not have been, so the count can be off in either direction.
 * The error is bounded by the previous window's count and, in practice, is small
 * enough that Cloudflare and others run this in production. Say so in the docs
 * rather than implying exactness.
 *
 * <h2>Implementation notes</h2>
 *
 * <p>Do the weighting in integer arithmetic:
 * {@code previousCount * (windowNanos - elapsed) / windowNanos}. Introducing a
 * {@code double} here reintroduces exactly the drift {@link
 * com.ratelimit.state.BucketState} avoids.
 *
 * <p>Roll the window the same way {@link FixedWindowLimiter} does, but note that
 * if more than two windows have elapsed, {@code previousCount} must be zeroed
 * rather than carried: the window before last is fully expired.
 *
 * <p>On rejection, the retry delay is the time until enough of the previous
 * window ages out to bring the estimate under the limit.
 */
final class SlidingWindowCounterLimiter extends AbstractRateLimiter {

    private final long limit;
    private final long windowNanos;

    private final StateMutation<WindowState> acquireMutation = this::acquireMutation;
    private final StateMutation<WindowState> peekMutation = this::peekMutation;
    private final LongFunction<WindowState> stateFactory = WindowState::new;

    SlidingWindowCounterLimiter(
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
        throw new UnsupportedOperationException("TODO: see the class JavaDoc for the algorithm");
    }

    private RateLimitResult peekMutation(WindowState state, long nowNanos, int permits) {
        throw new UnsupportedOperationException("TODO: see the class JavaDoc for the algorithm");
    }
}

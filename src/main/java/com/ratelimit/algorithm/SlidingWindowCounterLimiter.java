package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.listener.RateLimitListener;
import com.ratelimit.state.WindowState;
import com.ratelimit.store.LimiterStore;
import com.ratelimit.store.StateMutation;
import com.ratelimit.time.TimeSource;

import java.math.BigInteger;
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
        roll(state, nowNanos);

        long elapsedNanos = nowNanos - state.currentWindowStartNanos;
        long estimate = estimate(state.previousCount, state.currentCount, elapsedNanos);

        // Written as a subtraction rather than `estimate + permits <= limit`, which
        // could overflow for a limit near the top of the long range.
        if (estimate <= limit - permits) {
            state.currentCount += permits;
            return RateLimitResult.allowed(limit, limit - estimate - permits);
        }
        return RateLimitResult.rejected(limit, Duration.ofNanos(
                nanosUntilFits(state.previousCount, state.currentCount, elapsedNanos, permits)));
    }

    /**
     * The same decision, computed into local variables so the state is left exactly
     * as it was found.
     */
    private RateLimitResult peekMutation(WindowState state, long nowNanos, int permits) {
        long windowStart = projectedWindowStart(state, nowNanos);
        long windowsPassed = (windowStart - state.currentWindowStartNanos) / windowNanos;

        // The counters a roll would have left behind, without performing the roll.
        long previousCount;
        long currentCount;
        if (windowsPassed == 0) {
            previousCount = state.previousCount;
            currentCount = state.currentCount;
        } else {
            previousCount = (windowsPassed == 1) ? state.currentCount : 0L;
            currentCount = 0L;
        }

        long elapsedNanos = nowNanos - windowStart;
        long estimate = estimate(previousCount, currentCount, elapsedNanos);

        if (estimate <= limit - permits) {
            return RateLimitResult.allowed(limit, limit - estimate - permits);
        }
        return RateLimitResult.rejected(limit, Duration.ofNanos(
                nanosUntilFits(previousCount, currentCount, elapsedNanos, permits)));
    }

    /** Rotates the counters if the clock has crossed into a later window. */
    private void roll(WindowState state, long nowNanos) {
        long newStart = projectedWindowStart(state, nowNanos);
        if (newStart == state.currentWindowStartNanos) {
            return;
        }
        long windowsPassed = (newStart - state.currentWindowStartNanos) / windowNanos;
        // Carried only when exactly one window elapsed. If two or more did, the
        // window immediately behind us saw no traffic and must not be weighted in.
        state.previousCount = (windowsPassed == 1) ? state.currentCount : 0L;
        state.currentCount = 0L;
        state.currentWindowStartNanos = newStart;
    }

    /**
     * Where the current window starts. Advances by whole multiples so boundaries sit
     * on a fixed grid rather than drifting with call timing.
     */
    private long projectedWindowStart(WindowState state, long nowNanos) {
        long elapsed = nowNanos - state.currentWindowStartNanos;
        if (elapsed < windowNanos) {
            return state.currentWindowStartNanos;
        }
        return state.currentWindowStartNanos + (elapsed / windowNanos) * windowNanos;
    }

    /** {@code previousCount * (1 - elapsed/window) + currentCount}, in integers. */
    private long estimate(long previousCount, long currentCount, long elapsedNanos) {
        return carriedOver(previousCount, elapsedNanos) + currentCount;
    }

    /**
     * The previous window's share, decaying linearly to zero as the current window
     * fills. This is the approximation: it assumes those requests were spread evenly
     * across the window, so the estimate can err in either direction, bounded by
     * {@code previousCount}.
     */
    private long carriedOver(long previousCount, long elapsedNanos) {
        if (previousCount <= 0) {
            return 0L;
        }
        long remainingWeightNanos = windowNanos - elapsedNanos;
        if (remainingWeightNanos <= 0) {
            return 0L;
        }
        // Rounded up, not down. Rounding down would shave a fraction off the carried
        // count and let a request through fractionally before the reported retry
        // delay had elapsed, breaking the promise that waiting exactly that long is
        // what it takes. Erring high also keeps the estimate on the strict side.
        return mulDivCeil(previousCount, remainingWeightNanos, windowNanos);
    }

    /**
     * How long until enough of the previous window ages out for the request to fit.
     *
     * <p>Solved directly rather than by stepping: the carried share falls linearly,
     * so the instant it crosses the threshold is arithmetic. Never longer than the
     * time to the next roll, after which the previous count is gone entirely.
     */
    private long nanosUntilFits(long previousCount, long currentCount, long elapsedNanos, int permits) {
        long untilRollNanos = windowNanos - elapsedNanos;
        long headroom = limit - currentCount - permits;

        // Nothing left to decay, or this window's own count already breaches the
        // limit: only the roll can help.
        if (previousCount <= 0 || headroom < 0) {
            return untilRollNanos;
        }
        if (headroom >= previousCount) {
            return 0L; // the whole previous window could stay and it would still fit
        }
        // Need previousCount * (windowNanos - e) / windowNanos <= headroom.
        long tolerableWeightNanos = mulDiv(headroom, windowNanos, previousCount);
        long neededElapsedNanos = windowNanos - tolerableWeightNanos;
        return Math.min(untilRollNanos, Math.max(0L, neededElapsedNanos - elapsedNanos));
    }

    /**
     * {@code a * b / c} where the result always fits in a {@code long} but the
     * product may not — a large limit over a long window overflows readily.
     * Deliberately not floating point, which would reintroduce the drift this
     * library avoids everywhere else; the wide path allocates only in the rare case.
     */
    private static long mulDiv(long a, long b, long c) {
        try {
            return Math.multiplyExact(a, b) / c;
        } catch (ArithmeticException overflow) {
            return BigInteger.valueOf(a)
                    .multiply(BigInteger.valueOf(b))
                    .divide(BigInteger.valueOf(c))
                    .longValueExact();
        }
    }

    /**
     * {@link #mulDiv} rounded up. All three operands are non-negative here, so this
     * is a plain "add one unless it divided evenly" rather than the sign-aware form
     * {@code Math.ceilDiv} would give — and that method needs Java 18, above this
     * library's floor.
     */
    private static long mulDivCeil(long a, long b, long c) {
        try {
            long product = Math.multiplyExact(a, b);
            long quotient = product / c;
            return (product % c == 0) ? quotient : quotient + 1;
        } catch (ArithmeticException overflow) {
            BigInteger[] quotientAndRemainder = BigInteger.valueOf(a)
                    .multiply(BigInteger.valueOf(b))
                    .divideAndRemainder(BigInteger.valueOf(c));
            long quotient = quotientAndRemainder[0].longValueExact();
            return quotientAndRemainder[1].signum() == 0 ? quotient : quotient + 1;
        }
    }
}

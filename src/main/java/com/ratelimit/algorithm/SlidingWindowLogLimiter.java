package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.listener.RateLimitListener;
import com.ratelimit.state.LogState;
import com.ratelimit.store.LimiterStore;
import com.ratelimit.store.StateMutation;
import com.ratelimit.time.TimeSource;

import java.time.Duration;
import java.util.function.LongFunction;

/**
 * Sliding window log: keeps the timestamp of every granted permit and counts the
 * ones still inside the trailing window.
 *
 * <p>The only exact algorithm here. No boundary artefacts, no estimation. It is
 * the reference the approximate algorithms are tested against.
 *
 * <p>The cost is memory proportional to the limit: a limit of 10,000 per hour
 * retains up to 10,000 timestamps per active key. Use it when the limit is small
 * and exactness matters, or as an oracle in tests.
 *
 * <h2>Implementation notes</h2>
 *
 * <p>The mutation is:
 * <ol>
 *   <li>Prune: while the head of {@code state.timestampsNanos} is older than
 *       {@code nowNanos - windowNanos}, remove it. Amortised O(1) per entry,
 *       because each timestamp is added once and removed once.</li>
 *   <li>If {@code size() + permits <= limit}, append {@code nowNanos} once per
 *       permit and allow.</li>
 *   <li>Otherwise reject. The retry delay is the time until enough entries
 *       expire: look at the {@code permits}-th entry from the head and return
 *       {@code (thatTimestamp + windowNanos) - nowNanos}.</li>
 * </ol>
 *
 * <p>{@code peek} must not prune, since pruning is a mutation. Count the entries
 * newer than the cutoff by walking from the head instead.
 *
 * <p>Appending {@code permits} copies of the same timestamp is deliberate and
 * keeps the count and the log in agreement. A run-length encoded log would be
 * more compact but is not worth the complexity at this size.
 */
final class SlidingWindowLogLimiter extends AbstractRateLimiter {

    private final long limit;
    private final long windowNanos;

    private final StateMutation<LogState> acquireMutation = this::acquireMutation;
    private final StateMutation<LogState> peekMutation = this::peekMutation;
    private final LongFunction<LogState> stateFactory = now -> new LogState();

    SlidingWindowLogLimiter(
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

    private RateLimitResult acquireMutation(LogState state, long nowNanos, int permits) {
        throw new UnsupportedOperationException("TODO: see the class JavaDoc for the algorithm");
    }

    private RateLimitResult peekMutation(LogState state, long nowNanos, int permits) {
        throw new UnsupportedOperationException("TODO: see the class JavaDoc for the algorithm");
    }
}

package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.RateLimiter;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Applies several limiters at once. All must permit for the request to proceed.
 *
 * <p>The usual shape is a short burst limit alongside a long sustained limit:
 * 20 per second and 1,000 per hour. Neither expresses the policy alone.
 *
 * <h2>The permit leak, and why {@code peek} exists</h2>
 *
 * <p>The obvious implementation calls {@code tryAcquire} on each delegate until
 * one refuses. It is wrong. If the first delegate grants and the second refuses,
 * the request fails but the first has already spent a permit that nothing will
 * ever use. Under sustained load at the second limit, the first limiter's budget
 * drains to nothing and the composite refuses everything.
 *
 * <p>So the implementation is two-phase: {@code peek} every delegate, and only if
 * all of them would grant, {@code tryAcquire} every delegate.
 *
 * <h2>The race that remains, and why it is acceptable</h2>
 *
 * <p>Two-phase is not atomic. Between the peek pass and the acquire pass another
 * thread can consume the permit that was peeked, so a delegate can still refuse
 * during the acquire pass, and the delegates before it will have leaked.
 *
 * <p>Closing that hole entirely needs a lock spanning every delegate for the whole
 * decision, which serialises all keys through one monitor and costs far more
 * throughput than the leak costs accuracy. The leak is bounded — it requires a
 * concurrent decision on the same key at the boundary of a delegate's budget, and
 * it self-corrects as the leaked permits refill.
 *
 * <p>This is documented rather than quietly accepted. If a caller needs strict
 * composition they should express the policy as a single limiter instead.
 *
 * <h2>Reporting</h2>
 *
 * <p>The result returned is the one from the most constraining delegate, so that
 * {@code Retry-After} reflects the longest wait rather than the first refusal.
 */
final class CompositeRateLimiter implements RateLimiter {

    private final List<RateLimiter> delegates;

    CompositeRateLimiter(List<RateLimiter> delegates) {
        this.delegates = List.copyOf(Objects.requireNonNull(delegates, "delegates"));
        if (this.delegates.isEmpty()) {
            throw new IllegalArgumentException("a composite needs at least one delegate");
        }
    }

    @Override
    public RateLimitResult tryAcquire(String key, int permits) {
        // Phase one: would every delegate grant?
        RateLimitResult worstRefusal = null;
        for (RateLimiter delegate : delegates) {
            RateLimitResult peeked = delegate.peek(key, permits);
            if (!peeked.allowed() && isWorse(peeked, worstRefusal)) {
                worstRefusal = peeked;
            }
        }
        if (worstRefusal != null) {
            return worstRefusal;
        }

        // Phase two: commit. Every delegate said yes a moment ago.
        RateLimitResult tightest = null;
        for (RateLimiter delegate : delegates) {
            RateLimitResult result = delegate.tryAcquire(key, permits);
            if (!result.allowed()) {
                // Lost the race described above: a concurrent caller took the permit
                // between the two passes. Return the refusal rather than unwinding the
                // delegates already charged, which errs towards enforcing the limit.
                return result;
            }
            tightest = tighter(tightest, result);
        }
        return tightest;
    }

    @Override
    public RateLimitResult peek(String key, int permits) {
        RateLimitResult worstRefusal = null;
        RateLimitResult tightest = null;
        for (RateLimiter delegate : delegates) {
            RateLimitResult peeked = delegate.peek(key, permits);
            if (!peeked.allowed()) {
                if (isWorse(peeked, worstRefusal)) {
                    worstRefusal = peeked;
                }
            } else {
                tightest = tighter(tightest, peeked);
            }
        }
        return worstRefusal != null ? worstRefusal : tightest;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mirrors {@code AbstractRateLimiter.acquire}, but measures the deadline
     * against {@link System#nanoTime()} rather than an injected time source: a
     * composite owns no clock, and its delegates may each hold a different one.
     */
    @Override
    public RateLimitResult acquire(String key, int permits, Duration timeout)
            throws InterruptedException {

        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(timeout, "timeout");
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive: " + permits);
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        while (true) {
            RateLimitResult result = tryAcquire(key, permits);
            if (result.allowed()) {
                return result;
            }
            if (result.retryAfter().isZero()) {
                // Unsatisfiable for any amount of waiting, e.g. more permits than some
                // delegate's capacity. Sleeping would never help.
                return result;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return result;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(result.retryAfter().toNanos(), remainingNanos));
        }
    }

    @Override
    public void reset(String key) {
        delegates.forEach(delegate -> delegate.reset(key));
    }

    /** @return true if {@code candidate} demands a longer wait than {@code incumbent} */
    private static boolean isWorse(RateLimitResult candidate, RateLimitResult incumbent) {
        return incumbent == null
                || candidate.retryAfter().compareTo(incumbent.retryAfter()) > 0;
    }

    /**
     * @return whichever result leaves the caller less headroom, so that a reported
     *         {@code X-RateLimit-Remaining} never promises more requests than the
     *         strictest delegate would actually permit
     */
    private static RateLimitResult tighter(RateLimitResult incumbent, RateLimitResult candidate) {
        return (incumbent == null || candidate.remaining() < incumbent.remaining())
                ? candidate
                : incumbent;
    }
}

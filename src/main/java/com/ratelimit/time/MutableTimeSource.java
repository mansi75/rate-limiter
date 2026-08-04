package com.ratelimit.time;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link TimeSource} whose value only changes when you tell it to.
 *
 * <p>This class ships in the main artifact rather than the test artifact on
 * purpose: code that depends on a rate limiter needs to test its own behaviour
 * around limits, and it cannot do that without controlling time.
 *
 * <p>It is also why this library's own test suite runs in milliseconds. A test
 * that verifies "the bucket refills after one second" does not need to wait one
 * second; it advances time by one second.
 *
 * <pre>{@code
 * MutableTimeSource time = new MutableTimeSource();
 * RateLimiter limiter = RateLimiters.tokenBucket()
 *         .capacity(1)
 *         .refill(1, Duration.ofSeconds(1))
 *         .timeSource(time)
 *         .build();
 *
 * assertThat(limiter.isAllowed("k")).isTrue();
 * assertThat(limiter.isAllowed("k")).isFalse();
 * time.advance(Duration.ofSeconds(1));
 * assertThat(limiter.isAllowed("k")).isTrue();
 * }</pre>
 *
 * <p>This class is thread-safe.
 */
public final class MutableTimeSource implements TimeSource {

    private final AtomicLong nanos;

    /** Creates a time source starting at zero. */
    public MutableTimeSource() {
        this(0L);
    }

    /**
     * Creates a time source starting at the given value.
     *
     * @param startNanos initial reading
     */
    public MutableTimeSource(long startNanos) {
        this.nanos = new AtomicLong(startNanos);
    }

    @Override
    public long nanoTime() {
        return nanos.get();
    }

    /**
     * Moves time forward.
     *
     * @param duration amount to advance by; must not be negative
     * @throws IllegalArgumentException if {@code duration} is negative
     */
    public void advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("cannot advance by a negative duration: " + duration);
        }
        advanceNanos(duration.toNanos());
    }

    /**
     * Moves time forward by a raw nanosecond count.
     *
     * @param delta nanoseconds to advance by; must not be negative
     * @throws IllegalArgumentException if {@code delta} is negative
     */
    public void advanceNanos(long delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("cannot advance by a negative amount: " + delta);
        }
        nanos.addAndGet(delta);
    }
}

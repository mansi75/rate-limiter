package com.ratelimit.builder;

import com.ratelimit.RateLimiter;
import com.ratelimit.listener.RateLimitListener;
import com.ratelimit.store.InMemoryStore;
import com.ratelimit.store.LimiterStore;
import com.ratelimit.time.TimeSource;

import java.time.Duration;
import java.util.Objects;

/**
 * The options every algorithm shares, declared once.
 *
 * <p>The recursive type parameter is what lets the fluent chain survive
 * inheritance. Without it, {@code tokenBucket().timeSource(t).capacity(10)} would
 * not compile, because {@code timeSource} would return the base type and
 * {@code capacity} is not declared on it.
 *
 * @param <B> the concrete builder type
 */
public abstract class  AbstractLimiterBuilder<B extends AbstractLimiterBuilder<B>> {

    /** Where per-key state lives. Defaults to a bounded in-memory store. */
    protected LimiterStore store = InMemoryStore.withDefaults();
    /** The clock. Defaults to a monotonic system source. */
    protected TimeSource timeSource = TimeSource.system();
    /** Instrumentation hook. Defaults to doing nothing. */
    protected RateLimitListener listener = RateLimitListener.NOOP;

    /**
     * @param store where to keep per-key state
     * @return this builder
     * @throws NullPointerException if {@code store} is null
     */
    public B store(LimiterStore store) {
        this.store = Objects.requireNonNull(store, "store");
        return self();
    }

    /**
     * @param timeSource the clock; pass a
     *        {@link com.ratelimit.time.MutableTimeSource} in tests
     * @return this builder
     * @throws NullPointerException if {@code timeSource} is null
     */
    public B timeSource(TimeSource timeSource) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        return self();
    }

    /**
     * @param listener notified of every decision
     * @return this builder
     * @throws NullPointerException if {@code listener} is null
     */
    public B listener(RateLimitListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        return self();
    }

    /**
     * Validates the configuration and constructs the limiter.
     *
     * @return the configured limiter
     * @throws IllegalArgumentException if the configuration is incomplete or
     *                                  inconsistent
     */
    public abstract RateLimiter build();

    /** @return {@code this}, typed as the concrete builder */
    @SuppressWarnings("unchecked")
    protected final B self() {
        return (B) this;
    }

    /**
     * Shared validation for the rate-shaped builders.
     *
     * @param permits permits per period
     * @param period  the period
     * @param name    parameter name for the error message
     */
    protected static void requirePositiveRate(long permits, Duration period, String name) {
        if (permits <= 0) {
            throw new IllegalArgumentException(name + " permits must be positive: " + permits);
        }
        Objects.requireNonNull(period, name + " period");
        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException(name + " period must be positive: " + period);
        }
        if (period.toNanos() / permits == 0) {
            throw new IllegalArgumentException(
                    name + " is faster than one permit per nanosecond, which cannot be represented");
        }
    }

    /**
     * Guards against the capacity-times-cost multiplication overflowing.
     *
     * @param capacity       permit ceiling
     * @param nanosPerPermit cost of one permit
     */
    protected static void requireNoOverflow(long capacity, long nanosPerPermit) {
        if (capacity > Long.MAX_VALUE / nanosPerPermit) {
            throw new IllegalArgumentException(
                    "capacity " + capacity + " at this rate overflows the nanosecond budget; "
                            + "reduce the capacity or increase the rate");
        }
    }
}

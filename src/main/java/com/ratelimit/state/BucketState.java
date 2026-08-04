package com.ratelimit.state;

/**
 * State for the token and leaky bucket algorithms.
 *
 * <p>Tokens are not stored as a count. They are stored as {@code availableNanos},
 * the amount of refill time banked so far, where one permit costs a fixed number
 * of nanoseconds. This keeps the arithmetic in {@code long} rather than floating
 * point and stops slow refill rates from drifting.
 *
 * <p>Consider a limit of one permit per minute polled every second. Held as a
 * fractional token count, each poll adds 0.0166..., and the rounding error
 * accumulates. Held as nanos, each poll adds exactly 1,000,000,000, and one
 * permit costs exactly 60,000,000,000. No error is possible.
 */
public final class BucketState implements LimiterState {

    /** Banked refill time, in nanoseconds. Never negative, never above the cap. */
    public long availableNanos;

    /** Reading of the time source at the most recent refill. */
    public long lastRefillNanos;

    /**
     * @param availableNanos initial banked refill time
     * @param lastRefillNanos initial time source reading
     */
    public BucketState(long availableNanos, long lastRefillNanos) {
        this.availableNanos = availableNanos;
        this.lastRefillNanos = lastRefillNanos;
    }
}

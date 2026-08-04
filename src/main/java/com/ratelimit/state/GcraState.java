package com.ratelimit.state;

/**
 * State for the generic cell rate algorithm: a single timestamp.
 *
 * <p>GCRA keeps the "theoretical arrival time" of the next permit that would
 * exactly conform to the configured rate. Everything else, including how much
 * burst is still available, is derived from that one number and the current time.
 *
 * <p>One {@code long} per key makes this the cheapest algorithm in the library
 * by a wide margin, which is why it is what most production rate limiters
 * actually run.
 */
public final class GcraState implements LimiterState {

    /** Theoretical arrival time of the next conforming permit, in nanos. */
    public long tatNanos;

    /**
     * @param tatNanos initial theoretical arrival time
     */
    public GcraState(long tatNanos) {
        this.tatNanos = tatNanos;
    }
}

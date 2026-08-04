package com.ratelimit.store;

import com.ratelimit.RateLimitResult;
import com.ratelimit.state.LimiterState;

/**
 * A single rate limiting decision, expressed as a function of state and time.
 *
 * <p>Every algorithm in this library is written as one of these. The algorithm
 * reads and updates the state it is handed and returns a verdict; it does not
 * know where the state came from, how it is stored, or what guarantees that no
 * other thread is touching it at the same time. Those are the store's job.
 *
 * <p>Implementations must be free of side effects other than mutating the state
 * passed to them, and must not block. A {@link LimiterStore} may hold a lock
 * while running one.
 *
 * @param <S> the state shape this algorithm requires
 */
@FunctionalInterface
public interface StateMutation<S extends LimiterState> {

    /**
     * Applies the algorithm.
     *
     * @param state    the current state for the key, exclusively held
     * @param nowNanos the current time source reading
     * @param permits  how many permits are being requested
     * @return the decision
     */
    RateLimitResult apply(S state, long nowNanos, int permits);
}

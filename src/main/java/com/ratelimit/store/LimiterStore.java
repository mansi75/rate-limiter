package com.ratelimit.store;

import com.ratelimit.RateLimitResult;
import com.ratelimit.state.LimiterState;

import java.util.function.LongFunction;

/**
 * Holds per-key algorithm state and guarantees exclusive access to it.
 *
 * <p>This is the extension point of the library. Swapping the implementation is
 * what turns a single-process limiter into a distributed one; no algorithm code
 * changes.
 *
 * <h2>Why there is no get and put</h2>
 *
 * <p>A rate limiting decision is a read-modify-write: read the remaining permits,
 * decide, write the new value. Exposing separate {@code get} and {@code put}
 * would make that sequence non-atomic, and two threads holding the same key
 * could each read one remaining permit and each conclude they may proceed. The
 * single {@link #compute} method exists to make that mistake unrepresentable.
 *
 * <h2>Contract for implementations</h2>
 *
 * <ul>
 *   <li>{@code compute} must be atomic with respect to other {@code compute}
 *       calls on the same key. Calls on different keys should proceed
 *       independently; a single global lock satisfies correctness but destroys
 *       throughput.</li>
 *   <li>The state passed to the mutation must be the current state for that key,
 *       created via {@code stateFactory} if absent.</li>
 *   <li>Any mutation the function performs must be durable to subsequent calls.</li>
 * </ul>
 *
 * <p>A distributed implementation cannot satisfy the atomicity requirement with
 * client-side logic, because the read and the write are separate round trips. It
 * must push the mutation to the server: a Redis implementation would encode the
 * algorithm as a Lua script and invoke it with {@code EVALSHA}, so the read,
 * decision, and write happen inside a single-threaded server execution.
 *
 * <h2>Why time and permits are parameters</h2>
 *
 * <p>They could have been captured by the mutation lambda instead. Passing them
 * explicitly lets each algorithm hold a single bound method reference in a field,
 * created once at construction, rather than allocating a capturing lambda on
 * every call. On a hot path this is the difference between zero allocations per
 * decision and one.
 */
public interface LimiterStore {

    /**
     * Atomically applies {@code mutation} to the state held for {@code key}.
     *
     * @param <S>          the state shape
     * @param key          the key whose state to mutate
     * @param stateFactory creates the initial state when the key is unknown,
     *                     given the current time
     * @param mutation     the algorithm to run
     * @param nowNanos     the current time source reading
     * @param permits      how many permits the caller is requesting
     * @return whatever the mutation returned
     */
    <S extends LimiterState> RateLimitResult compute(
            String key,
            LongFunction<S> stateFactory,
            StateMutation<S> mutation,
            long nowNanos,
            int permits);

    /**
     * Discards the state for a single key.
     *
     * @param key the key to forget
     */
    void reset(String key);

    /** Discards all state. */
    void clear();

    /**
     * @return the number of keys currently holding state
     */
    int size();
}

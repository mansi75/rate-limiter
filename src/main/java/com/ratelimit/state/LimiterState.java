package com.ratelimit.state;

/**
 * Marker for the per-key state an algorithm keeps between calls.
 *
 * <p>Implementations are <strong>mutable and not thread-safe by themselves</strong>.
 * That is deliberate. State objects are only ever touched from inside a
 * {@link com.ratelimit.store.StateMutation}, and the
 * {@link com.ratelimit.store.LimiterStore} contract guarantees that
 * at most one mutation runs against a given key at a time. Pushing the exclusion
 * into the store means the algorithms contain no synchronisation at all, and the
 * same algorithm code works unchanged against an in-memory map or a remote store.
 *
 * <p>Implementations should stay small. They are held for every live key, so a
 * few extra fields multiply by the cardinality of the key space.
 */
public interface LimiterState {
}

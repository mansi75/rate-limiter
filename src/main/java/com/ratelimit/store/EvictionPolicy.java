package com.ratelimit.store;

/**
 * How an in-memory store chooses which key to discard when it reaches capacity.
 *
 * <p>Eviction is a correctness concern, not a tuning knob. Rate limiter keys are
 * usually derived from untrusted input such as a client IP or an API token, so an
 * unbounded map is an unbounded memory leak that any caller can trigger. Evicting
 * a key resets its limit, which is a small fairness cost; running out of heap is
 * not a small cost.
 */
public enum EvictionPolicy {

    /**
     * Discard the key that has gone longest without a decision. The right default:
     * a key that has not been seen recently has almost certainly refilled anyway,
     * so discarding it grants nothing that time would not have granted.
     */
    LEAST_RECENTLY_USED,

    /**
     * Discard the key with the fewest decisions recorded. Retains heavy hitters,
     * which is what you want when the limiter's job is to hold back abusers rather
     * than to be fair to everyone.
     */
    LEAST_FREQUENTLY_USED,

    /**
     * Never evict. Only safe when the key space is small and closed, for example
     * a fixed set of downstream service names.
     */
    NONE
}

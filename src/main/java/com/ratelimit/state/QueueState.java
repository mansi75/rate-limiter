package com.ratelimit.state;

/**
 * State for the leaky bucket algorithm.
 *
 * <p>Deliberately not {@link BucketState}, though the two hold the same shape. The
 * sign is inverted: a token bucket banks credit it may spend, a leaky bucket
 * accumulates backlog it must drain. One field named {@code availableNanos} meaning
 * both "what I have" and "what I owe" reads as a bug at every call site, and costs
 * more in confusion than a second two-field class costs in code.
 *
 * <p>Fill is held in nanoseconds of drain time rather than as a request count, for
 * the same reason {@link BucketState} banks nanoseconds: the arithmetic stays in
 * {@code long}, and a slow leak rate cannot drift. One queued request occupies
 * {@code nanosPerPermit} nanoseconds of the bucket.
 */
public final class QueueState implements LimiterState {

    /**
     * How full the bucket is, in nanoseconds of drain time. Never negative, never
     * above the configured capacity.
     */
    public long fillNanos;

    /** Reading of the time source at the most recent drain. */
    public long lastDrainNanos;

    /**
     * Creates an empty bucket. A leaky bucket has no {@code initiallyFull} option:
     * a queue nobody has joined is empty, and starting one partly full would refuse
     * a client that has done nothing.
     *
     * @param lastDrainNanos initial time source reading
     */
    public QueueState(long lastDrainNanos) {
        this.fillNanos = 0L;
        this.lastDrainNanos = lastDrainNanos;
    }
}

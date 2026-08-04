package com.ratelimit.state;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * State for the sliding window log algorithm: the timestamp of every permit
 * granted inside the current window.
 *
 * <p>This is the only algorithm in the library whose memory grows with the limit
 * rather than staying constant. A limit of 10,000 per hour means up to 10,000
 * longs retained per active key. That cost buys exact accuracy, with no boundary
 * artefacts and no approximation. The trade-off is documented rather than hidden;
 * callers who cannot pay it should use the sliding window counter.
 *
 * <p>A deque is used because entries always expire from the head and are always
 * appended at the tail, so pruning is amortised O(1) per expired entry.
 */
public final class LogState implements LimiterState {

    /** Timestamps of granted permits, oldest first. */
    public final Deque<Long> timestampsNanos = new ArrayDeque<>();
}

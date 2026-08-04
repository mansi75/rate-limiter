package com.ratelimit.state;

/**
 * State for the fixed window and sliding window counter algorithms.
 *
 * <p>The fixed window uses {@code currentWindowStartNanos} and {@code currentCount}
 * only. The sliding window counter additionally keeps {@code previousCount} so it
 * can weight the tail of the preceding window.
 */
public final class WindowState implements LimiterState {

    /** Time source reading at which the current window opened. */
    public long currentWindowStartNanos;

    /** Permits consumed in the current window. */
    public long currentCount;

    /** Permits consumed in the window immediately before the current one. */
    public long previousCount;

    /**
     * @param currentWindowStartNanos time source reading at window open
     */
    public WindowState(long currentWindowStartNanos) {
        this.currentWindowStartNanos = currentWindowStartNanos;
        this.currentCount = 0L;
        this.previousCount = 0L;
    }
}

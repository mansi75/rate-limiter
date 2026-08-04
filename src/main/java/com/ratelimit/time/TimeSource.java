package com.ratelimit.time;

/**
 * Supplies the current time to rate limiting algorithms.
 *
 * <p>This interface deliberately does <em>not</em> use {@link java.time.Clock}.
 * {@code Clock} exposes wall-clock time, which can jump backwards when the system
 * clock is corrected (NTP adjustment, manual change, virtual machine migration).
 * A backwards jump would let a caller replay a window that has already elapsed,
 * which is a correctness bug in a rate limiter. Every implementation of this
 * interface must therefore be <strong>monotonic</strong>: successive calls to
 * {@link #nanoTime()} must never return a smaller value than a previous call.
 *
 * <p>The absolute value returned is meaningless. Only differences between two
 * readings carry information.
 *
 * @see #system()
 */
@FunctionalInterface
public interface TimeSource {

    /**
     * Returns the current value of a monotonic nanosecond timer.
     *
     * @return elapsed nanoseconds from an arbitrary but fixed origin
     */
    long nanoTime();

    /**
     * Returns the default time source, backed by {@link System#nanoTime()}.
     *
     * @return a monotonic system-backed time source
     */
    static TimeSource system() {
        return System::nanoTime;
    }
}

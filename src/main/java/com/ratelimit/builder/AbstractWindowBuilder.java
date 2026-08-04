package com.ratelimit.builder;

import java.time.Duration;
import java.util.Objects;

/**
 * Shared options for the three window-shaped algorithms: a permit ceiling and a
 * window length.
 *
 * @param <B> the concrete builder type
 */
public abstract class AbstractWindowBuilder<B extends AbstractWindowBuilder<B>>
        extends AbstractLimiterBuilder<B> {

    /** Permits allowed per window. */
    protected long limit = -1;
    /** The window length. */
    protected Duration window;

    /**
     * @param limit permits allowed per window; must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    public B limit(long limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        this.limit = limit;
        return self();
    }

    /**
     * @param window the window length; must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code window} is not positive
     */
    public B window(Duration window) {
        Objects.requireNonNull(window, "window");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive: " + window);
        }
        this.window = window;
        return self();
    }

    /** @throws IllegalArgumentException if either required option is missing */
    protected final void requireComplete() {
        if (limit < 0) {
            throw new IllegalArgumentException("limit is required");
        }
        if (window == null) {
            throw new IllegalArgumentException("window is required");
        }
    }
}

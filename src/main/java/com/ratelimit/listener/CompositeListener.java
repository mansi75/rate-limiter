package com.ratelimit.listener;

import com.ratelimit.RateLimitResult;

import java.util.List;
import java.util.Objects;

/**
 * Fans every callback out to several listeners, in the order supplied.
 *
 * <p>An exception from one listener would otherwise abort the rest and propagate
 * into the caller's request path. Since a listener is instrumentation and the
 * decision has already been made, that trade is wrong: exceptions are swallowed
 * so that a broken metrics sink cannot take down the thing it is measuring.
 */
public final class CompositeListener implements RateLimitListener {

    private final List<RateLimitListener> delegates;

    private CompositeListener(List<RateLimitListener> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    /**
     * @param listeners the listeners to notify, in order
     * @return a listener fanning out to all of them
     * @throws NullPointerException if any listener is null
     */
    public static RateLimitListener of(RateLimitListener... listeners) {
        Objects.requireNonNull(listeners, "listeners");
        return new CompositeListener(List.of(listeners));
    }

    @Override
    public void onAllowed(String key, RateLimitResult result) {
        for (RateLimitListener delegate : delegates) {
            try {
                delegate.onAllowed(key, result);
            } catch (RuntimeException ignored) {
                // Instrumentation must not break the request path.
            }
        }
    }

    @Override
    public void onRejected(String key, RateLimitResult result) {
        for (RateLimitListener delegate : delegates) {
            try {
                delegate.onRejected(key, result);
            } catch (RuntimeException ignored) {
                // Instrumentation must not break the request path.
            }
        }
    }

    @Override
    public void onReset(String key) {
        for (RateLimitListener delegate : delegates) {
            try {
                delegate.onReset(key);
            } catch (RuntimeException ignored) {
                // Instrumentation must not break the request path.
            }
        }
    }
}

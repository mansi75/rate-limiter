package com.ratelimit.builder;

import com.ratelimit.RateLimiter;
import com.ratelimit.algorithm.Algorithms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Combines several limiters so that all of them must permit a request.
 *
 * <pre>{@code
 * RateLimiter limiter = RateLimiters.composite()
 *         .add(RateLimiters.tokenBucket()
 *                 .capacity(20).refill(10, Duration.ofSeconds(1)).build())
 *         .add(RateLimiters.slidingWindowCounter()
 *                 .limit(1000).window(Duration.ofHours(1)).build())
 *         .build();
 * }</pre>
 *
 * <p>Order the delegates cheapest first. The composite peeks every delegate
 * before committing, so a delegate that refuses early saves the cost of the rest.
 */
public final class CompositeBuilder {

    private final List<RateLimiter> delegates = new ArrayList<>();

    /** Use {@link com.ratelimit.RateLimiters#composite()}. */
    public CompositeBuilder() {
    }

    /**
     * @param delegate a limiter that must also permit the request
     * @return this builder
     * @throws NullPointerException if {@code delegate} is null
     */
    public CompositeBuilder add(RateLimiter delegate) {
        delegates.add(Objects.requireNonNull(delegate, "delegate"));
        return this;
    }

    /**
     * @return the composite limiter
     * @throws IllegalArgumentException if no delegate was added
     */
    public RateLimiter build() {
        if (delegates.isEmpty()) {
            throw new IllegalArgumentException("a composite needs at least one delegate");
        }
        return Algorithms.composite(delegates);
    }
}

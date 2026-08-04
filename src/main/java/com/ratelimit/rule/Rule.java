package com.ratelimit.rule;

import com.ratelimit.RateLimiter;

import java.util.Objects;

/**
 * A matcher paired with the limiter that applies to the keys it matches.
 *
 * @param matcher which keys this rule governs
 * @param limiter the limit to apply to them
 */
public record Rule(KeyMatcher matcher, RateLimiter limiter) {

    /** @throws NullPointerException if either component is null */
    public Rule {
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(limiter, "limiter");
    }
}

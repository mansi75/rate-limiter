package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.RateLimiter;
import com.ratelimit.rule.Rule;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Routes each key to a different limiter according to the first matching rule.
 *
 * <p>Lets one limiter express a whole policy: generous limits for paying users,
 * tight limits for anonymous traffic, no limit at all for internal callers.
 *
 * <pre>{@code
 * RateLimiters.rules()
 *         .when(KeyMatcher.prefix("internal:")).unlimited()
 *         .when(KeyMatcher.prefix("premium:")).use(generous)
 *         .otherwise(strict)
 *         .build();
 * }</pre>
 *
 * <h2>Ordering is part of the contract</h2>
 *
 * <p>Rules are evaluated in the order they were declared and the first match
 * wins. That is observable behaviour, not an implementation detail: with
 * overlapping matchers, reordering the rules changes the outcome. It is
 * documented here so that callers can rely on it and so that no future change
 * "optimises" the list into a map.
 *
 * <p>Matching is linear in the number of rules and runs on every decision. Keep
 * rule lists short, and put the matcher that fires most often first.
 */
final class RuleBasedRateLimiter implements RateLimiter {

    private final List<Rule> rules;
    private final RateLimiter fallback;

    RuleBasedRateLimiter(List<Rule> rules, RateLimiter fallback) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /**
     * @param key the key to route
     * @return the limiter governing {@code key}
     */
    private RateLimiter limiterFor(String key) {
        for (Rule rule : rules) {
            if (rule.matcher().test(key)) {
                return rule.limiter();
            }
        }
        return fallback;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int permits) {
        return limiterFor(Objects.requireNonNull(key, "key")).tryAcquire(key, permits);
    }

    @Override
    public RateLimitResult peek(String key, int permits) {
        return limiterFor(Objects.requireNonNull(key, "key")).peek(key, permits);
    }

    @Override
    public RateLimitResult acquire(String key, int permits, Duration timeout)
            throws InterruptedException {
        return limiterFor(Objects.requireNonNull(key, "key")).acquire(key, permits, timeout);
    }

    @Override
    public void reset(String key) {
        limiterFor(Objects.requireNonNull(key, "key")).reset(key);
    }
}

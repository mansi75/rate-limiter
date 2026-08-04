package com.ratelimit.algorithm;

import com.ratelimit.RateLimiter;
import com.ratelimit.listener.RateLimitListener;
import com.ratelimit.rule.Rule;
import com.ratelimit.store.LimiterStore;
import com.ratelimit.time.TimeSource;

import java.time.Duration;
import java.util.List;

/**
 * The bridge between the builders and the package-private algorithm classes.
 *
 * <p>The implementations are package-private so that nothing outside this package
 * can bind to {@code TokenBucketLimiter} as a type and freeze it as public API.
 * The builders live in a different package and still need to construct them, so
 * this class exposes construction without exposing the classes.
 *
 * <p>Callers should use {@link com.ratelimit.RateLimiters} instead.
 * This class is public only because Java has no friend packages; it is not part
 * of the supported surface.
 */
public final class Algorithms {

    private Algorithms() {
    }

    /** @return a token bucket limiter */
    public static RateLimiter tokenBucket(
            long capacity, long refillPermits, Duration refillPeriod, boolean initiallyFull,
            LimiterStore store, TimeSource timeSource, RateLimitListener listener) {
        return new TokenBucketLimiter(
                capacity, refillPermits, refillPeriod, initiallyFull, store, timeSource, listener);
    }

    /** @return a leaky bucket limiter */
    public static RateLimiter leakyBucket(
            long capacity, long leakPermits, Duration leakPeriod,
            LimiterStore store, TimeSource timeSource, RateLimitListener listener) {
        return new LeakyBucketLimiter(
                capacity, leakPermits, leakPeriod, store, timeSource, listener);
    }

    /** @return a fixed window limiter */
    public static RateLimiter fixedWindow(
            long limit, Duration window,
            LimiterStore store, TimeSource timeSource, RateLimitListener listener) {
        return new FixedWindowLimiter(limit, window, store, timeSource, listener);
    }

    /** @return a sliding window log limiter */
    public static RateLimiter slidingWindowLog(
            long limit, Duration window,
            LimiterStore store, TimeSource timeSource, RateLimitListener listener) {
        return new SlidingWindowLogLimiter(limit, window, store, timeSource, listener);
    }

    /** @return a sliding window counter limiter */
    public static RateLimiter slidingWindowCounter(
            long limit, Duration window,
            LimiterStore store, TimeSource timeSource, RateLimitListener listener) {
        return new SlidingWindowCounterLimiter(limit, window, store, timeSource, listener);
    }

    /** @return a GCRA limiter */
    public static RateLimiter gcra(
            long ratePermits, Duration ratePeriod, long burst,
            LimiterStore store, TimeSource timeSource, RateLimitListener listener) {
        return new GcraLimiter(ratePermits, ratePeriod, burst, store, timeSource, listener);
    }

    /** @return a limiter requiring every delegate to permit */
    public static RateLimiter composite(List<RateLimiter> delegates) {
        return new CompositeRateLimiter(delegates);
    }

    /** @return a limiter routing keys to delegates by rule */
    public static RateLimiter ruleBased(List<Rule> rules, RateLimiter fallback) {
        return new RuleBasedRateLimiter(rules, fallback);
    }
}

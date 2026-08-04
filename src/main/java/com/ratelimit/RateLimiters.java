package com.ratelimit;

import com.ratelimit.builder.CompositeBuilder;
import com.ratelimit.builder.FixedWindowBuilder;
import com.ratelimit.builder.GcraBuilder;
import com.ratelimit.builder.LeakyBucketBuilder;
import com.ratelimit.builder.RuleSetBuilder;
import com.ratelimit.builder.SlidingWindowCounterBuilder;
import com.ratelimit.builder.SlidingWindowLogBuilder;
import com.ratelimit.builder.TokenBucketBuilder;

/**
 * Entry point for constructing every limiter in the library.
 *
 * <p>One import, and the algorithm names read as prose at the call site:
 *
 * <pre>{@code
 * import static com.ratelimit.RateLimiters.tokenBucket;
 *
 * RateLimiter limiter = tokenBucket()
 *         .capacity(100)
 *         .refill(10, Duration.ofSeconds(1))
 *         .build();
 * }</pre>
 *
 * <p>Not instantiable.
 */
public final class RateLimiters {

    private RateLimiters() {
        throw new AssertionError("no instances");
    }

    /**
     * Permits accumulate up to a ceiling; bursts are allowed up to that ceiling.
     * The right default for most callers.
     *
     * @return a token bucket builder
     */
    public static TokenBucketBuilder tokenBucket() {
        return new TokenBucketBuilder();
    }

    /**
     * Output is smoothed to a constant rate; bursts are refused rather than
     * absorbed. Use when instantaneous rate matters more than average rate.
     *
     * @return a leaky bucket builder
     */
    public static LeakyBucketBuilder leakyBucket() {
        return new LeakyBucketBuilder();
    }

    /**
     * A counter that resets on a boundary. Cheapest and least accurate; permits
     * twice the limit across a boundary.
     *
     * @return a fixed window builder
     */
    public static FixedWindowBuilder fixedWindow() {
        return new FixedWindowBuilder();
    }

    /**
     * Exact counting over a trailing window, at memory proportional to the limit.
     *
     * @return a sliding window log builder
     */
    public static SlidingWindowLogBuilder slidingWindowLog() {
        return new SlidingWindowLogBuilder();
    }

    /**
     * Approximate counting over a trailing window at constant memory. The best
     * default of the window algorithms.
     *
     * @return a sliding window counter builder
     */
    public static SlidingWindowCounterBuilder slidingWindowCounter() {
        return new SlidingWindowCounterBuilder();
    }

    /**
     * Token bucket behaviour from a single timestamp. The cheapest algorithm here.
     *
     * @return a GCRA builder
     */
    public static GcraBuilder gcra() {
        return new GcraBuilder();
    }

    /**
     * Requires several limiters to permit a request, for policies like
     * "20 per second and 1,000 per hour".
     *
     * @return a composite builder
     */
    public static CompositeBuilder composite() {
        return new CompositeBuilder();
    }

    /**
     * Routes keys to different limits by pattern.
     *
     * @return a rule set builder
     */
    public static RuleSetBuilder rules() {
        return new RuleSetBuilder();
    }
}

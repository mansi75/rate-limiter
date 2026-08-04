package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.RateLimiter;

import java.time.Duration;

/**
 * Grants everything. The null object for this library.
 *
 * <p>Exists so that "this class of caller is exempt" is expressed as a rule like
 * any other, rather than as a null check or a flag threaded through
 * {@link RuleBasedRateLimiter}. Also useful for disabling limiting in a test or
 * an environment without changing the surrounding code.
 */
public final class UnlimitedRateLimiter implements RateLimiter {

    /** The single instance; the class is stateless. */
    public static final RateLimiter INSTANCE = new UnlimitedRateLimiter();

    private UnlimitedRateLimiter() {
    }

    @Override
    public RateLimitResult tryAcquire(String key, int permits) {
        return RateLimitResult.allowed(Long.MAX_VALUE, Long.MAX_VALUE);
    }

    @Override
    public RateLimitResult peek(String key, int permits) {
        return RateLimitResult.allowed(Long.MAX_VALUE, Long.MAX_VALUE);
    }

    @Override
    public RateLimitResult acquire(String key, int permits, Duration timeout) {
        return RateLimitResult.allowed(Long.MAX_VALUE, Long.MAX_VALUE);
    }

    @Override
    public void reset(String key) {
        // Nothing is retained.
    }
}

package com.ratelimit.algorithm;

import com.ratelimit.RateLimitResult;
import com.ratelimit.RateLimiter;
import com.ratelimit.RateLimiters;
import com.ratelimit.time.MutableTimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowCounterLimiterTest {

    private MutableTimeSource time;

    @BeforeEach
    void setUp() {
        time = new MutableTimeSource();
    }

    private RateLimiter limiter(long limit, Duration window) {
        return RateLimiters.slidingWindowCounter()
                .limit(limit)
                .window(window)
                .timeSource(time)
                .build();
    }

    private int drain(RateLimiter limiter, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.isAllowed("client")) {
                allowed++;
            }
        }
        return allowed;
    }

    @Nested
    @DisplayName("within a single window")
    class WithinOneWindow {

        @Test
        @DisplayName("permits up to the limit, then refuses")
        void allowsUpToLimit() {
            assertThat(drain(limiter(3, Duration.ofMinutes(1)), 10)).isEqualTo(3);
        }

        @Test
        @DisplayName("reports the permits left")
        void reportsRemaining() {
            RateLimiter limiter = limiter(5, Duration.ofMinutes(1));

            assertThat(limiter.tryAcquire("client", 2).remaining()).isEqualTo(3);
            assertThat(limiter.tryAcquire("client", 1).remaining()).isEqualTo(2);
        }

        @Test
        @DisplayName("a multi-permit request is all or nothing")
        void multiPermitIsAtomic() {
            RateLimiter limiter = limiter(5, Duration.ofMinutes(1));
            limiter.tryAcquire("client", 4);

            assertThat(limiter.tryAcquire("client", 2).allowed()).isFalse();
            assertThat(limiter.tryAcquire("client", 1).allowed()).isTrue();
        }

        @Test
        @DisplayName("keys count independently")
        void keysAreIndependent() {
            RateLimiter limiter = limiter(1, Duration.ofMinutes(1));

            assertThat(limiter.isAllowed("a")).isTrue();
            assertThat(limiter.isAllowed("b")).isTrue();
            assertThat(limiter.isAllowed("a")).isFalse();
        }

        @Test
        @DisplayName("a request larger than the limit is refused with a zero delay")
        void oversizedRequestIsUnsatisfiable() {
            RateLimitResult result = limiter(3, Duration.ofMinutes(1)).tryAcquire("client", 4);

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfter()).isZero();
        }
    }

    @Nested
    @DisplayName("the weighted estimate")
    class Estimate {

        @Test
        @DisplayName("matches the worked example in the class javadoc")
        void matchesTheDocumentedExample() {
            // limit 100, window 60s. Previous window recorded 100, current 20, and we
            // are 15s (a quarter) into the current window.
            // estimate = 100 * 0.75 + 20 = 95, so five permits remain.
            RateLimiter limiter = limiter(100, Duration.ofSeconds(60));
            limiter.tryAcquire("client", 100);

            time.advance(Duration.ofSeconds(75));
            limiter.tryAcquire("client", 20);

            assertThat(limiter.peek("client", 5).allowed()).isTrue();
            assertThat(limiter.peek("client", 6).allowed()).isFalse();
        }

        @Test
        @DisplayName("the previous window decays linearly rather than all at once")
        void decaysLinearly() {
            RateLimiter limiter = limiter(10, Duration.ofSeconds(10));
            drain(limiter, 10);                       // window one full

            time.advance(Duration.ofSeconds(10));     // window two, previous counts 10
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advance(Duration.ofSeconds(5));      // halfway: previous counts 5
            assertThat(drain(limiter, 10)).isEqualTo(5);
        }

        @Test
        @DisplayName("a window with no traffic clears the estimate entirely")
        void idleWindowClearsTheEstimate() {
            RateLimiter limiter = limiter(5, Duration.ofSeconds(10));
            drain(limiter, 5);

            // Two windows on, so the window immediately behind us saw nothing.
            time.advance(Duration.ofSeconds(20));

            assertThat(drain(limiter, 10)).isEqualTo(5);
        }

        @Test
        @DisplayName("weighting stays exact for a limit that overflows a long product")
        void handlesHugeLimits() {
            // limit x windowNanos exceeds Long.MAX_VALUE, so the naive product wraps.
            RateLimiter limiter = limiter(10_000_000L, Duration.ofHours(1));

            assertThat(limiter.tryAcquire("client", 6_000_000).allowed()).isTrue();
            assertThat(limiter.tryAcquire("client", 5_000_000).allowed()).isFalse();
            assertThat(limiter.tryAcquire("client", 4_000_000).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("the boundary this algorithm exists to fix")
    class BoundaryFix {

        private static final int LIMIT = 100;
        private static final Duration WINDOW = Duration.ofMinutes(1);

        @Test
        @DisplayName("refuses the burst a fixed window would have let through")
        void closesTheFixedWindowHole() {
            RateLimiter limiter = limiter(LIMIT, WINDOW);

            // Anchor the window at t=0 so the boundary falls at t=60s.
            assertThat(limiter.isAllowed("client")).isTrue();

            time.advance(Duration.ofSeconds(59));
            assertThat(1 + drain(limiter, LIMIT - 1)).isEqualTo(LIMIT);

            // The boundary a fixed window would reset at. The previous window still
            // counts in full, because no time has elapsed to decay it.
            time.advance(Duration.ofSeconds(1));
            assertThat(drain(limiter, LIMIT)).isZero();
        }

        @Test
        @DisplayName("a fixed window with the same traffic allows twice the limit")
        void fixedWindowDoesNot() {
            RateLimiter limiter = RateLimiters.fixedWindow()
                    .limit(LIMIT).window(WINDOW).timeSource(time).build();

            assertThat(limiter.isAllowed("client")).isTrue();
            time.advance(Duration.ofSeconds(59));
            int before = 1 + drain(limiter, LIMIT - 1);

            time.advance(Duration.ofSeconds(1));
            int after = drain(limiter, LIMIT);

            assertThat(before + after).isEqualTo(2 * LIMIT);
        }
    }

    @Nested
    @DisplayName("the retry delay")
    class RetryDelay {

        @Test
        @DisplayName("is the moment the estimate drops far enough, and honouring it works")
        void retryAfterIsExact() {
            RateLimiter limiter = limiter(10, Duration.ofSeconds(10));
            drain(limiter, 10);
            time.advance(Duration.ofSeconds(10));     // rolled; estimate is still 10

            RateLimitResult refused = limiter.tryAcquire("client");
            assertThat(refused.allowed()).isFalse();
            assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(1));

            time.advanceNanos(refused.retryAfter().toNanos() - 1);
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advanceNanos(1);
            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("never exceeds one window")
        void retryAfterIsBoundedByTheWindow() {
            RateLimiter limiter = limiter(1, Duration.ofSeconds(10));
            limiter.tryAcquire("client");

            RateLimitResult refused = limiter.tryAcquire("client");

            assertThat(refused.retryAfter()).isLessThanOrEqualTo(Duration.ofSeconds(10));
        }
    }

    @Nested
    @DisplayName("sustained pressure")
    class SustainedPressure {

        @Test
        @DisplayName("holds close to the configured rate over many windows")
        void staysNearTheLimit() {
            RateLimiter limiter = limiter(10, Duration.ofSeconds(10));

            int allowed = 0;
            for (int second = 0; second < 100; second++) {
                allowed += drain(limiter, 20);
                time.advance(Duration.ofSeconds(1));
            }

            // Ten windows at 10 per window is 100. The estimate's smoothing keeps it
            // at or under that, and well above a naive half-rate.
            assertThat(allowed).isLessThanOrEqualTo(100);
            assertThat(allowed).isGreaterThan(80);
        }
    }

    @Nested
    @DisplayName("peek")
    class Peek {

        @Test
        @DisplayName("consumes nothing")
        void peekConsumesNothing() {
            RateLimiter limiter = limiter(2, Duration.ofMinutes(1));

            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            assertThat(limiter.peek("client", 1).allowed()).isTrue();

            assertThat(drain(limiter, 5)).isEqualTo(2);
        }

        @Test
        @DisplayName("sees the window roll without performing it")
        void peekSeesTheRoll() {
            RateLimiter limiter = limiter(2, Duration.ofSeconds(10));
            drain(limiter, 2);
            assertThat(limiter.peek("client", 1).allowed()).isFalse();

            // Two windows on, the estimate clears completely.
            time.advance(Duration.ofSeconds(20));

            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("agrees with tryAcquire about the retry delay")
        void peekAgreesOnRetryAfter() {
            RateLimiter limiter = limiter(10, Duration.ofSeconds(10));
            drain(limiter, 10);
            time.advance(Duration.ofSeconds(10));

            assertThat(limiter.peek("client", 1).retryAfter())
                    .isEqualTo(limiter.tryAcquire("client", 1).retryAfter());
        }
    }
}

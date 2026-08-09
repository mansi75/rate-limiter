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

class GcraLimiterTest {

    private MutableTimeSource time;

    @BeforeEach
    void setUp() {
        time = new MutableTimeSource();
    }

    /** 10 permits per second, so one permit every 100ms. */
    private RateLimiter limiter(long burst) {
        return RateLimiters.gcra()
                .rate(10, Duration.ofSeconds(1))
                .burst(burst)
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
    @DisplayName("spacing")
    class Spacing {

        @Test
        @DisplayName("a burst of 1 produces a perfectly even stream")
        void burstOfOneIsPerfectlyEven() {
            RateLimiter limiter = limiter(1);

            assertThat(drain(limiter, 10)).isEqualTo(1);

            time.advance(Duration.ofMillis(100));
            assertThat(drain(limiter, 10)).isEqualTo(1);

            // 99ms is not quite an interval.
            time.advance(Duration.ofMillis(99));
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advance(Duration.ofMillis(1));
            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("burst(n) lets n requests run ahead of schedule")
        void burstAllowsRunningAhead() {
            assertThat(drain(limiter(5), 10)).isEqualTo(5);
        }

        @Test
        @DisplayName("burst credit returns one interval at a time")
        void burstRecoversGradually() {
            RateLimiter limiter = limiter(3);
            drain(limiter, 3);

            time.advance(Duration.ofMillis(100));
            assertThat(drain(limiter, 5)).isEqualTo(1);

            time.advance(Duration.ofMillis(300));
            assertThat(drain(limiter, 5)).isEqualTo(3);
        }

        @Test
        @DisplayName("idle time does not accumulate beyond the burst")
        void idleDoesNotBankUnboundedBurst() {
            RateLimiter limiter = limiter(3);

            // An hour of silence is still worth only the configured burst, because
            // the arrival time is clamped forward to now rather than left behind.
            time.advance(Duration.ofHours(1));

            assertThat(drain(limiter, 100)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("the long-run rate")
    class LongRunRate {

        @Test
        @DisplayName("holds at the configured rate however hard the caller pushes")
        void sustainsTheConfiguredRate() {
            RateLimiter limiter = limiter(5);

            int allowed = 0;
            for (int millis = 0; millis < 10_000; millis++) {
                if (limiter.isAllowed("client")) {
                    allowed++;
                }
                time.advance(Duration.ofMillis(1));
            }

            // The burst of 5 goes in the first 5ms, then one permit every 100ms at
            // t=100ms .. 9900ms, which is 99 more.
            assertThat(allowed).isEqualTo(5 + 99);
        }
    }

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("remaining counts down the burst allowance")
        void remainingTracksBurst() {
            RateLimiter limiter = limiter(3);

            assertThat(limiter.tryAcquire("client").remaining()).isEqualTo(2);
            assertThat(limiter.tryAcquire("client").remaining()).isEqualTo(1);
            assertThat(limiter.tryAcquire("client").remaining()).isZero();
        }

        @Test
        @DisplayName("the retry delay is exact, and honouring it works")
        void retryAfterIsExact() {
            RateLimiter limiter = limiter(1);
            limiter.tryAcquire("client");

            RateLimitResult refused = limiter.tryAcquire("client");
            assertThat(refused.allowed()).isFalse();
            assertThat(refused.retryAfter()).isEqualTo(Duration.ofMillis(100));

            time.advanceNanos(refused.retryAfter().toNanos() - 1);
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advanceNanos(1);
            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("a request larger than the burst is refused with a zero delay")
        void oversizedRequestIsUnsatisfiable() {
            RateLimitResult result = limiter(2).tryAcquire("client", 3);

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfter()).isZero();
        }
    }

    @Nested
    @DisplayName("multi-permit and isolation")
    class MultiPermit {

        @Test
        @DisplayName("a multi-permit request costs one interval per permit")
        void costsOneIntervalPerPermit() {
            RateLimiter limiter = limiter(4);

            assertThat(limiter.tryAcquire("client", 3).allowed()).isTrue();
            assertThat(limiter.tryAcquire("client", 2).allowed()).isFalse();
            assertThat(limiter.tryAcquire("client", 1).allowed()).isTrue();
            assertThat(limiter.tryAcquire("client", 1).allowed()).isFalse();
        }

        @Test
        @DisplayName("keys are independent")
        void keysAreIndependent() {
            RateLimiter limiter = limiter(1);

            assertThat(limiter.isAllowed("a")).isTrue();
            assertThat(limiter.isAllowed("b")).isTrue();
            assertThat(limiter.isAllowed("a")).isFalse();
        }
    }

    @Nested
    @DisplayName("peek")
    class Peek {

        @Test
        @DisplayName("consumes nothing")
        void peekConsumesNothing() {
            RateLimiter limiter = limiter(2);

            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            assertThat(limiter.peek("client", 1).remaining()).isEqualTo(1);

            assertThat(drain(limiter, 5)).isEqualTo(2);
        }

        @Test
        @DisplayName("agrees with tryAcquire about a refusal")
        void peekAgreesOnRefusal() {
            RateLimiter limiter = limiter(1);
            limiter.tryAcquire("client");

            RateLimitResult peeked = limiter.peek("client", 1);
            assertThat(peeked.allowed()).isFalse();
            assertThat(peeked.retryAfter()).isEqualTo(Duration.ofMillis(100));
        }
    }
}

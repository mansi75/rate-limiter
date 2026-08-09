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

/**
 * The fixed window counter, including the boundary flaw it exists to demonstrate.
 */
class FixedWindowBoundaryTest {

    private MutableTimeSource time;

    @BeforeEach
    void setUp() {
        time = new MutableTimeSource();
    }

    private RateLimiter limiter(long limit, Duration window) {
        return RateLimiters.fixedWindow()
                .limit(limit)
                .window(window)
                .timeSource(time)
                .build();
    }

    /** @return how many of {@code attempts} single-permit requests were allowed */
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
            RateLimiter limiter = limiter(3, Duration.ofMinutes(1));

            assertThat(limiter.isAllowed("client")).isTrue();
            assertThat(limiter.isAllowed("client")).isTrue();
            assertThat(limiter.isAllowed("client")).isTrue();
            assertThat(limiter.isAllowed("client")).isFalse();
        }

        @Test
        @DisplayName("reports the permits left in the window")
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
            // The refused request consumed nothing, so the last permit is still there.
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
    }

    @Nested
    @DisplayName("the retry delay")
    class RetryDelay {

        @Test
        @DisplayName("points at the moment the window rolls, and honouring it works")
        void retryAfterIsTimeUntilRoll() {
            RateLimiter limiter = limiter(1, Duration.ofMinutes(1));
            limiter.tryAcquire("client");

            time.advance(Duration.ofSeconds(20));
            RateLimitResult refused = limiter.tryAcquire("client");

            assertThat(refused.allowed()).isFalse();
            assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(40));

            // One nanosecond short is still inside the window.
            time.advanceNanos(refused.retryAfter().toNanos() - 1);
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advanceNanos(1);
            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("a request larger than the limit is refused with a zero delay")
        void oversizedRequestIsUnsatisfiable() {
            RateLimiter limiter = limiter(3, Duration.ofMinutes(1));

            RateLimitResult result = limiter.tryAcquire("client", 4);

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfter()).isZero();
        }
    }

    @Nested
    @DisplayName("window rolling")
    class Rolling {

        @Test
        @DisplayName("the counter resets when the window rolls")
        void resetsOnRoll() {
            RateLimiter limiter = limiter(2, Duration.ofMinutes(1));
            assertThat(drain(limiter, 5)).isEqualTo(2);

            time.advance(Duration.ofMinutes(1));

            assertThat(drain(limiter, 5)).isEqualTo(2);
        }

        @Test
        @DisplayName("skipping many idle windows still resets exactly once")
        void skippingWindowsIsHandled() {
            RateLimiter limiter = limiter(2, Duration.ofMinutes(1));
            drain(limiter, 5);

            time.advance(Duration.ofHours(3));

            assertThat(drain(limiter, 5)).isEqualTo(2);
        }

        @Test
        @DisplayName("boundaries sit on a fixed grid rather than drifting with call timing")
        void windowsDoNotDrift() {
            RateLimiter limiter = limiter(1, Duration.ofMinutes(1));
            limiter.tryAcquire("client");                 // window one: [0, 60)

            // First call of window two arrives late, at t = 90s.
            time.advance(Duration.ofSeconds(90));
            assertThat(limiter.isAllowed("client")).isTrue();

            // If the window had re-anchored to t=90, it would run to t=150 and this
            // would be refused. On a fixed grid the window is [60,120), so t=120 rolls.
            time.advance(Duration.ofSeconds(30));         // t = 120s
            assertThat(limiter.isAllowed("client")).isTrue();
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
            assertThat(limiter.peek("client", 1).remaining()).isEqualTo(1);

            assertThat(drain(limiter, 5)).isEqualTo(2);
        }

        @Test
        @DisplayName("sees the window roll without performing it")
        void peekSeesTheRoll() {
            RateLimiter limiter = limiter(1, Duration.ofMinutes(1));
            limiter.tryAcquire("client");
            assertThat(limiter.peek("client", 1).allowed()).isFalse();

            time.advance(Duration.ofMinutes(1));

            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            // The peek did not roll the window itself; the real call still works.
            assertThat(limiter.isAllowed("client")).isTrue();
        }
    }

    @Nested
    @DisplayName("the boundary flaw this algorithm exists to demonstrate")
    class BoundaryFlaw {

        private static final int LIMIT = 100;
        private static final Duration WINDOW = Duration.ofMinutes(1);

        @Test
        @DisplayName("a fixed window permits 2x the limit across a boundary")
        void permitsDoubleTheLimitAtTheBoundary() {
            RateLimiter limiter = limiter(LIMIT, WINDOW);

            // A window is anchored at a key's first request, so open one at t=0.
            // That makes the boundary fall at t=60s.
            assertThat(limiter.isAllowed("client")).isTrue();

            // 11:00:59 - the last second of the window. Fill it to the limit.
            time.advance(Duration.ofSeconds(59));
            int beforeBoundary = 1 + drain(limiter, LIMIT - 1);

            // 11:01:00 - one second later, and the counter has reset.
            time.advance(Duration.ofSeconds(1));
            int afterBoundary = drain(limiter, LIMIT);

            assertThat(beforeBoundary).isEqualTo(LIMIT);
            assertThat(afterBoundary).isEqualTo(LIMIT);
            // 200 requests inside one second, against a limit of 100 per minute.
            assertThat(beforeBoundary + afterBoundary).isEqualTo(2 * LIMIT);
        }

        @Test
        @DisplayName("a token bucket refuses the same traffic")
        void tokenBucketHoldsTheLine() {
            RateLimiter limiter = RateLimiters.tokenBucket()
                    .capacity(LIMIT)
                    .refill(LIMIT, WINDOW)
                    .timeSource(time)
                    .build();

            assertThat(limiter.isAllowed("client")).isTrue();

            time.advance(Duration.ofSeconds(59));
            int beforeBoundary = 1 + drain(limiter, LIMIT - 1);

            time.advance(Duration.ofSeconds(1));
            int afterBoundary = drain(limiter, LIMIT);

            assertThat(beforeBoundary).isEqualTo(LIMIT);
            // No boundary to reset at: one second of refill at 100/minute is worth
            // 1.67 permits, so the burst is 2 rather than another full 100.
            assertThat(afterBoundary).isEqualTo(2);
        }
    }
}

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

class SlidingWindowLogLimiterTest {

    private MutableTimeSource time;

    @BeforeEach
    void setUp() {
        time = new MutableTimeSource();
    }

    private RateLimiter limiter(long limit, Duration window) {
        return RateLimiters.slidingWindowLog()
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
    @DisplayName("counting")
    class Counting {

        @Test
        @DisplayName("permits up to the limit, then refuses")
        void allowsUpToLimit() {
            assertThat(drain(limiter(3, Duration.ofSeconds(10)), 10)).isEqualTo(3);
        }

        @Test
        @DisplayName("reports the permits left")
        void reportsRemaining() {
            RateLimiter limiter = limiter(5, Duration.ofSeconds(10));

            assertThat(limiter.tryAcquire("client", 2).remaining()).isEqualTo(3);
            assertThat(limiter.tryAcquire("client", 1).remaining()).isEqualTo(2);
        }

        @Test
        @DisplayName("a multi-permit request is all or nothing")
        void multiPermitIsAtomic() {
            RateLimiter limiter = limiter(5, Duration.ofSeconds(10));
            limiter.tryAcquire("client", 4);

            assertThat(limiter.tryAcquire("client", 2).allowed()).isFalse();
            assertThat(limiter.tryAcquire("client", 1).allowed()).isTrue();
        }

        @Test
        @DisplayName("a refused request leaves nothing in the log")
        void refusalLogsNothing() {
            RateLimiter limiter = limiter(1, Duration.ofSeconds(5));
            limiter.tryAcquire("client");                 // logged at t=0

            time.advance(Duration.ofSeconds(4));
            assertThat(limiter.isAllowed("client")).isFalse();

            // Had the refusal been logged at t=4s, this would still be blocked.
            time.advance(Duration.ofSeconds(1));
            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("keys log independently")
        void keysAreIndependent() {
            RateLimiter limiter = limiter(1, Duration.ofSeconds(10));

            assertThat(limiter.isAllowed("a")).isTrue();
            assertThat(limiter.isAllowed("b")).isTrue();
            assertThat(limiter.isAllowed("a")).isFalse();
        }

        @Test
        @DisplayName("a request larger than the limit is refused with a zero delay")
        void oversizedRequestIsUnsatisfiable() {
            RateLimitResult result = limiter(3, Duration.ofSeconds(10)).tryAcquire("client", 4);

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfter()).isZero();
        }
    }

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("each permit frees exactly one window after it was taken")
        void permitsExpireIndividually() {
            RateLimiter limiter = limiter(2, Duration.ofSeconds(10));

            limiter.tryAcquire("client");                 // t = 0
            time.advance(Duration.ofSeconds(4));
            limiter.tryAcquire("client");                 // t = 4
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advance(Duration.ofSeconds(6));          // t = 10, first expires
            assertThat(limiter.isAllowed("client")).isTrue();
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advance(Duration.ofSeconds(4));          // t = 14, second expires
            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("the window follows the requests, so there is no boundary to exploit")
        void hasNoExploitableBoundary() {
            RateLimiter limiter = limiter(100, Duration.ofMinutes(1));

            time.advance(Duration.ofSeconds(59));
            assertThat(drain(limiter, 100)).isEqualTo(100);

            // Where a fixed window would reset. The log still holds all 100.
            time.advance(Duration.ofSeconds(1));
            assertThat(limiter.isAllowed("client")).isFalse();
        }

        @Test
        @DisplayName("a fully idle window frees everything")
        void idleWindowFreesEverything() {
            RateLimiter limiter = limiter(3, Duration.ofSeconds(10));
            drain(limiter, 3);

            time.advance(Duration.ofHours(1));

            assertThat(drain(limiter, 10)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("the retry delay")
    class RetryDelay {

        @Test
        @DisplayName("points at the oldest permit's expiry, and honouring it works")
        void retryAfterIsExact() {
            RateLimiter limiter = limiter(1, Duration.ofSeconds(5));
            limiter.tryAcquire("client");

            time.advance(Duration.ofSeconds(2));
            RateLimitResult refused = limiter.tryAcquire("client");

            assertThat(refused.allowed()).isFalse();
            assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(3));

            time.advanceNanos(refused.retryAfter().toNanos() - 1);
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advanceNanos(1);
            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("waits for as many permits as the request actually needs")
        void multiPermitWaitsForEnoughExpiries() {
            RateLimiter limiter = limiter(3, Duration.ofSeconds(10));

            limiter.tryAcquire("client");                 // t = 0
            time.advance(Duration.ofSeconds(1));
            limiter.tryAcquire("client");                 // t = 1
            time.advance(Duration.ofSeconds(1));          // t = 2

            // Two live, one slot free, two wanted: needs the t=0 permit to expire.
            // Not the second-oldest, which is what "the permits-th entry" would give.
            RateLimitResult refused = limiter.tryAcquire("client", 2);
            assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(8));

            time.advance(refused.retryAfter());
            assertThat(limiter.tryAcquire("client", 2).allowed()).isTrue();
        }

        @Test
        @DisplayName("a full log needs the permits-th oldest to expire")
        void fullLogWaitsForThePermitsThOldest() {
            RateLimiter limiter = limiter(3, Duration.ofSeconds(10));

            limiter.tryAcquire("client");                 // t = 0
            time.advance(Duration.ofSeconds(1));
            limiter.tryAcquire("client");                 // t = 1
            time.advance(Duration.ofSeconds(1));
            limiter.tryAcquire("client");                 // t = 2, log now full

            // Three live, three wanted: all three must age out, so wait for the newest.
            RateLimitResult refused = limiter.tryAcquire("client", 3);
            assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(10));
        }
    }

    @Nested
    @DisplayName("peek")
    class Peek {

        @Test
        @DisplayName("consumes nothing and logs nothing")
        void peekConsumesNothing() {
            RateLimiter limiter = limiter(2, Duration.ofSeconds(10));

            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            assertThat(limiter.peek("client", 1).allowed()).isTrue();

            assertThat(drain(limiter, 5)).isEqualTo(2);
        }

        @Test
        @DisplayName("sees expiry without pruning")
        void peekSeesExpiryWithoutPruning() {
            RateLimiter limiter = limiter(1, Duration.ofSeconds(5));
            limiter.tryAcquire("client");
            assertThat(limiter.peek("client", 1).allowed()).isFalse();

            time.advance(Duration.ofSeconds(5));

            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            // The peek did not prune, so the real call still finds and drops it.
            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("agrees with tryAcquire about the retry delay")
        void peekAgreesOnRetryAfter() {
            RateLimiter limiter = limiter(2, Duration.ofSeconds(10));
            drain(limiter, 2);
            time.advance(Duration.ofSeconds(3));

            assertThat(limiter.peek("client", 1).retryAfter())
                    .isEqualTo(limiter.tryAcquire("client", 1).retryAfter());
        }
    }

    @Nested
    @DisplayName("as the oracle the approximate algorithms are measured against")
    class AsOracle {

        @Test
        @DisplayName("is exact where the fixed window over-grants at a boundary")
        void exactWhereFixedWindowIsNot() {
            RateLimiter exact = limiter(100, Duration.ofMinutes(1));
            RateLimiter approximate = RateLimiters.fixedWindow()
                    .limit(100).window(Duration.ofMinutes(1)).timeSource(time).build();

            // Anchor both windows at t=0.
            exact.tryAcquire("client");
            approximate.tryAcquire("client");

            time.advance(Duration.ofSeconds(59));
            drain(exact, 99);
            drain(approximate, 99);

            time.advance(Duration.ofSeconds(1));
            int exactAfter = drain(exact, 100);
            int approximateAfter = drain(approximate, 100);

            // Exactly one permit has aged out at t=60s: the one taken at t=0, a full
            // window earlier. The log grants that one and no more, which is the
            // correct answer rather than a conservative one.
            assertThat(exactAfter).isEqualTo(1);
            // The fixed window resets its counter and hands out another hundred.
            assertThat(approximateAfter).isEqualTo(100);
        }

        @Test
        @DisplayName("never grants more than the limit over any trailing window")
        void neverExceedsTheLimitOverAnyWindow() {
            RateLimiter limiter = limiter(10, Duration.ofSeconds(10));

            // Hammer for 100 seconds, recording the second each grant landed in.
            int[] grantsPerSecond = new int[100];
            for (int second = 0; second < 100; second++) {
                grantsPerSecond[second] = drain(limiter, 20);
                time.advance(Duration.ofSeconds(1));
            }

            // Every 10-second span must hold at most 10 grants.
            for (int start = 0; start + 10 <= 100; start++) {
                int inWindow = 0;
                for (int i = start; i < start + 10; i++) {
                    inWindow += grantsPerSecond[i];
                }
                assertThat(inWindow).isLessThanOrEqualTo(10);
            }
        }
    }
}

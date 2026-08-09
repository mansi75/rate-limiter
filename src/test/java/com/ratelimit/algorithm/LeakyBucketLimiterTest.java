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

class LeakyBucketLimiterTest {

    private MutableTimeSource time;

    @BeforeEach
    void setUp() {
        time = new MutableTimeSource();
    }

    private RateLimiter limiter(long capacity, long leakPermits, Duration per) {
        return RateLimiters.leakyBucket()
                .capacity(capacity)
                .leak(leakPermits, per)
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
    @DisplayName("filling the bucket")
    class Filling {

        @Test
        @DisplayName("a new key starts with an empty bucket")
        void startsEmpty() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));

            // Unlike the token bucket there is no initiallyFull option: an empty
            // bucket is the only sensible start for a queue.
            assertThat(limiter.tryAcquire("client").remaining()).isEqualTo(2);
        }

        @Test
        @DisplayName("accepts up to the queue depth, then refuses")
        void refusesOnceFull() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));

            assertThat(drain(limiter, 10)).isEqualTo(3);
        }

        @Test
        @DisplayName("reports the room left in the queue")
        void reportsRemaining() {
            RateLimiter limiter = limiter(5, 1, Duration.ofSeconds(1));

            assertThat(limiter.tryAcquire("client", 2).remaining()).isEqualTo(3);
            assertThat(limiter.tryAcquire("client", 1).remaining()).isEqualTo(2);
        }

        @Test
        @DisplayName("a multi-permit request is all or nothing")
        void multiPermitIsAtomic() {
            RateLimiter limiter = limiter(5, 1, Duration.ofSeconds(1));
            limiter.tryAcquire("client", 4);

            assertThat(limiter.tryAcquire("client", 2).allowed()).isFalse();
            // The refusal took no room, so the last slot is still free.
            assertThat(limiter.tryAcquire("client", 1).allowed()).isTrue();
        }

        @Test
        @DisplayName("keys queue independently")
        void keysAreIndependent() {
            RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));

            assertThat(limiter.isAllowed("a")).isTrue();
            assertThat(limiter.isAllowed("b")).isTrue();
            assertThat(limiter.isAllowed("a")).isFalse();
        }
    }

    @Nested
    @DisplayName("draining")
    class Draining {

        @Test
        @DisplayName("drains at exactly the leak rate")
        void drainsAtLeakRate() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));
            drain(limiter, 3);                            // full

            time.advance(Duration.ofSeconds(1));
            assertThat(drain(limiter, 5)).isEqualTo(1);   // exactly one slot freed

            time.advance(Duration.ofSeconds(2));
            assertThat(drain(limiter, 5)).isEqualTo(2);   // two slots freed
        }

        @Test
        @DisplayName("never drains below empty, however long the key sits idle")
        void neverDrainsBelowEmpty() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));
            limiter.tryAcquire("client");

            time.advance(Duration.ofHours(1));

            // An hour of leaking cannot make the bucket emptier than empty, so the
            // room available is the queue depth and no more.
            assertThat(drain(limiter, 10)).isEqualTo(3);
        }

        @Test
        @DisplayName("a partial drain frees no whole slot")
        void partialDrainFreesNothing() {
            RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));
            limiter.tryAcquire("client");

            time.advance(Duration.ofMillis(999));
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advance(Duration.ofMillis(1));
            assertThat(limiter.isAllowed("client")).isTrue();
        }
    }

    @Nested
    @DisplayName("the retry delay")
    class RetryDelay {

        @Test
        @DisplayName("is the time to drain enough room, and honouring it works")
        void retryAfterIsExact() {
            RateLimiter limiter = limiter(2, 1, Duration.ofSeconds(1));
            drain(limiter, 2);

            RateLimitResult refused = limiter.tryAcquire("client");
            assertThat(refused.allowed()).isFalse();
            assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(1));

            time.advanceNanos(refused.retryAfter().toNanos() - 1);
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advanceNanos(1);
            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("scales with how many permits are wanted")
        void retryAfterScalesWithPermits() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));
            drain(limiter, 3);                            // full

            // One slot needs 1s of leaking; three slots need 3s.
            assertThat(limiter.tryAcquire("client", 1).retryAfter()).isEqualTo(Duration.ofSeconds(1));
            assertThat(limiter.tryAcquire("client", 3).retryAfter()).isEqualTo(Duration.ofSeconds(3));
        }

        @Test
        @DisplayName("a request larger than the queue is refused with a zero delay")
        void oversizedRequestIsUnsatisfiable() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));

            RateLimitResult result = limiter.tryAcquire("client", 4);

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfter()).isZero();
        }
    }

    @Nested
    @DisplayName("smoothing, which is the point of this algorithm")
    class Smoothing {

        @Test
        @DisplayName("a queue depth of 1 admits a perfectly even stream")
        void depthOneIsPerfectlySmooth() {
            RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));

            // Hammered ten times at t=0, exactly one gets in.
            assertThat(drain(limiter, 10)).isEqualTo(1);

            // Then exactly one per second, no matter how hard the caller pushes.
            int allowed = 0;
            for (int second = 0; second < 10; second++) {
                time.advance(Duration.ofSeconds(1));
                allowed += drain(limiter, 10);
            }
            assertThat(allowed).isEqualTo(10);
        }

        @Test
        @DisplayName("output stays at the leak rate under sustained pressure")
        void holdsTheLeakRateUnderPressure() {
            RateLimiter limiter = limiter(5, 2, Duration.ofSeconds(1));

            int allowed = 0;
            // Checked every 100ms, from t=0 to t=9.9s.
            for (int tick = 0; tick < 100; tick++) {
                allowed += drain(limiter, 5);
                time.advance(Duration.ofMillis(100));
            }

            // The queue of 5 fills instantly at t=0, then one slot frees every 500ms
            // (2 per second): at t=0.5s, 1.0s ... 9.5s, which is 19 more. The 20th
            // would land at t=10.0s, one tick past the end of the loop.
            assertThat(allowed).isEqualTo(5 + 19);
        }
    }

    @Nested
    @DisplayName("peek")
    class Peek {

        @Test
        @DisplayName("consumes no room")
        void peekConsumesNothing() {
            RateLimiter limiter = limiter(2, 1, Duration.ofSeconds(1));

            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            assertThat(limiter.peek("client", 1).remaining()).isEqualTo(1);

            assertThat(drain(limiter, 5)).isEqualTo(2);
        }

        @Test
        @DisplayName("sees the drain without performing it")
        void peekSeesTheDrain() {
            RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));
            limiter.tryAcquire("client");
            assertThat(limiter.peek("client", 1).allowed()).isFalse();

            time.advance(Duration.ofSeconds(1));

            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            assertThat(limiter.isAllowed("client")).isTrue();
        }
    }
}

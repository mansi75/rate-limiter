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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour of the token bucket.
 *
 * <p>Every test drives a {@link MutableTimeSource}, so the whole class runs in
 * microseconds and nothing depends on wall-clock timing. A test suite that sleeps
 * is a test suite that is flaky on a loaded CI runner.
 */
class TokenBucketLimiterTest {

    private MutableTimeSource time;

    @BeforeEach
    void setUp() {
        time = new MutableTimeSource();
    }

    private RateLimiter limiter(long capacity, long refillPermits, Duration per) {
        return RateLimiters.tokenBucket()
                .capacity(capacity)
                .refill(refillPermits, per)
                .timeSource(time)
                .build();
    }

    @Nested
    @DisplayName("burst behaviour")
    class Burst {

        @Test
        @DisplayName("allows a full bucket immediately, then refuses")
        void allowsBurstUpToCapacity() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));

            assertThat(limiter.isAllowed("k")).isTrue();
            assertThat(limiter.isAllowed("k")).isTrue();
            assertThat(limiter.isAllowed("k")).isTrue();
            assertThat(limiter.isAllowed("k")).isFalse();
        }

        @Test
        @DisplayName("a cold bucket can be configured to start empty")
        void coldStart() {
            RateLimiter limiter = RateLimiters.tokenBucket()
                    .capacity(3)
                    .refill(1, Duration.ofSeconds(1))
                    .initiallyFull(false)
                    .timeSource(time)
                    .build();

            assertThat(limiter.isAllowed("k")).isFalse();
            time.advance(Duration.ofSeconds(1));
            assertThat(limiter.isAllowed("k")).isTrue();
        }
    }

    @Nested
    @DisplayName("refill")
    class Refill {

        @Test
        @DisplayName("grants exactly one permit per refill period")
        void refillsAtConfiguredRate() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));
            drain(limiter, "k");

            time.advance(Duration.ofSeconds(1));
            assertThat(limiter.isAllowed("k")).isTrue();
            assertThat(limiter.isAllowed("k")).isFalse();
        }

        @Test
        @DisplayName("never accumulates beyond capacity, however long the key idles")
        void saturatesAtCapacity() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));
            drain(limiter, "k");

            time.advance(Duration.ofHours(1));

            assertThat(limiter.isAllowed("k")).isTrue();
            assertThat(limiter.isAllowed("k")).isTrue();
            assertThat(limiter.isAllowed("k")).isTrue();
            assertThat(limiter.isAllowed("k")).isFalse();
        }

        /**
         * The regression test for the reason permits are counted in nanoseconds.
         *
         * <p>Held as a fractional token count, a rate of one per minute polled once
         * a second adds 0.0166... each time and the rounding error compounds. This
         * test polls 59 times, asserts nothing was granted, then advances the last
         * second and asserts a permit appears exactly on time.
         */
        @Test
        @DisplayName("does not drift when a slow rate is polled frequently")
        void doesNotDriftOnSlowRates() {
            RateLimiter limiter = RateLimiters.tokenBucket()
                    .capacity(1)
                    .refill(1, Duration.ofMinutes(1))
                    .initiallyFull(false)
                    .timeSource(time)
                    .build();

            for (int second = 0; second < 59; second++) {
                assertThat(limiter.isAllowed("k")).isFalse();
                time.advance(Duration.ofSeconds(1));
            }
            assertThat(limiter.isAllowed("k")).isFalse();

            time.advance(Duration.ofSeconds(1));
            assertThat(limiter.isAllowed("k")).isTrue();
        }
    }

    @Nested
    @DisplayName("multiple permits")
    class MultiplePermits {

        @Test
        @DisplayName("takes all requested permits or none")
        void allOrNothing() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));

            assertThat(limiter.tryAcquire("k", 2).allowed()).isTrue();
            assertThat(limiter.tryAcquire("k", 2).allowed()).isFalse();
            assertThat(limiter.tryAcquire("k", 1).allowed()).isTrue();
        }

        @Test
        @DisplayName("refuses a request larger than capacity without a retry hint")
        void refusesUnsatisfiableRequest() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));

            RateLimitResult result = limiter.tryAcquire("k", 99);

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfter()).isZero();
        }

        @Test
        @DisplayName("rejects a non-positive permit count")
        void rejectsInvalidPermits() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));

            assertThatThrownBy(() -> limiter.tryAcquire("k", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("permits must be positive");
        }
    }

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("reports how many permits are left")
        void reportsRemaining() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));

            assertThat(limiter.tryAcquire("k").remaining()).isEqualTo(2);
            assertThat(limiter.tryAcquire("k").remaining()).isEqualTo(1);
            assertThat(limiter.tryAcquire("k").remaining()).isZero();
        }

        @Test
        @DisplayName("reports exactly how long until the next permit")
        void reportsRetryAfter() {
            RateLimiter limiter = limiter(3, 1, Duration.ofSeconds(1));
            drain(limiter, "k");

            assertThat(limiter.tryAcquire("k").retryAfter()).isEqualTo(Duration.ofSeconds(1));

            time.advance(Duration.ofMillis(400));
            assertThat(limiter.tryAcquire("k").retryAfter()).isEqualTo(Duration.ofMillis(600));
        }
    }

    @Nested
    @DisplayName("keys")
    class Keys {

        @Test
        @DisplayName("tracks each key independently")
        void keysAreIndependent() {
            RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));

            assertThat(limiter.isAllowed("a")).isTrue();
            assertThat(limiter.isAllowed("a")).isFalse();
            assertThat(limiter.isAllowed("b")).isTrue();
        }

        @Test
        @DisplayName("reset restores a key to full")
        void resetClearsState() {
            RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));
            assertThat(limiter.isAllowed("a")).isTrue();
            assertThat(limiter.isAllowed("a")).isFalse();

            limiter.reset("a");

            assertThat(limiter.isAllowed("a")).isTrue();
        }
    }

    @Nested
    @DisplayName("peek")
    class Peek {

        @Test
        @DisplayName("reports the decision without consuming anything")
        void peekDoesNotConsume() {
            RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));

            assertThat(limiter.peek("k", 1).allowed()).isTrue();
            assertThat(limiter.peek("k", 1).allowed()).isTrue();
            assertThat(limiter.peek("k", 1).allowed()).isTrue();

            assertThat(limiter.tryAcquire("k").allowed()).isTrue();
            assertThat(limiter.peek("k", 1).allowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("refuses to build without a capacity")
        void capacityRequired() {
            assertThatThrownBy(() -> RateLimiters.tokenBucket()
                    .refill(1, Duration.ofSeconds(1))
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity is required");
        }

        @Test
        @DisplayName("refuses to build without a refill rate")
        void refillRequired() {
            assertThatThrownBy(() -> RateLimiters.tokenBucket()
                    .capacity(10)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refill is required");
        }

        @Test
        @DisplayName("rejects a configuration whose nanosecond budget would overflow")
        void rejectsOverflowingConfiguration() {
            assertThatThrownBy(() -> RateLimiters.tokenBucket()
                    .capacity(Long.MAX_VALUE)
                    .refill(1, Duration.ofHours(1))
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overflows");
        }
    }

    private static void drain(RateLimiter limiter, String key) {
        while (limiter.isAllowed(key)) {
            // consume everything currently available
        }
    }
}

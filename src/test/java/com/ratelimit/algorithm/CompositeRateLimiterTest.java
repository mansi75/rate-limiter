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

class CompositeRateLimiterTest {

    private MutableTimeSource time;

    @BeforeEach
    void setUp() {
        time = new MutableTimeSource();
    }

    private RateLimiter tokenBucket(long capacity, long permits, Duration per) {
        return RateLimiters.tokenBucket()
                .capacity(capacity).refill(permits, per).timeSource(time).build();
    }

    private RateLimiter fixedWindow(long limit, Duration window) {
        return RateLimiters.fixedWindow()
                .limit(limit).window(window).timeSource(time).build();
    }

    @Nested
    @DisplayName("enforcing every delegate")
    class EnforcingAll {

        @Test
        @DisplayName("a burst limit and a sustained limit are both applied")
        void bothLimitsApply() {
            RateLimiter burst = tokenBucket(2, 2, Duration.ofSeconds(1));
            RateLimiter sustained = fixedWindow(3, Duration.ofHours(1));
            RateLimiter limiter = RateLimiters.composite().add(burst).add(sustained).build();

            // The burst limit bites first.
            assertThat(limiter.isAllowed("client")).isTrue();
            assertThat(limiter.isAllowed("client")).isTrue();
            assertThat(limiter.isAllowed("client")).isFalse();

            time.advance(Duration.ofSeconds(1));

            // Now the sustained limit is what remains.
            assertThat(limiter.isAllowed("client")).isTrue();
            assertThat(limiter.isAllowed("client")).isFalse();
        }

        @Test
        @DisplayName("reports the delegate with the least headroom")
        void reportsTheTightestDelegate() {
            RateLimiter generous = tokenBucket(1_000, 1, Duration.ofSeconds(1));
            RateLimiter tight = tokenBucket(5, 1, Duration.ofSeconds(1));
            RateLimiter limiter = RateLimiters.composite().add(generous).add(tight).build();

            RateLimitResult result = limiter.tryAcquire("client");

            // Reporting the generous one would promise 999 more requests that the
            // tight delegate would refuse.
            assertThat(result.limit()).isEqualTo(5);
            assertThat(result.remaining()).isEqualTo(4);
        }

        @Test
        @DisplayName("reports the longest wait when several delegates refuse")
        void reportsTheLongestWait() {
            RateLimiter quick = tokenBucket(1, 1, Duration.ofSeconds(1));
            RateLimiter slow = tokenBucket(1, 1, Duration.ofSeconds(10));
            RateLimiter limiter = RateLimiters.composite().add(quick).add(slow).build();

            limiter.tryAcquire("client");
            RateLimitResult refused = limiter.tryAcquire("client");

            assertThat(refused.allowed()).isFalse();
            // Waiting only 1s would just be refused again by the slow delegate.
            assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(10));
        }
    }

    @Nested
    @DisplayName("the permit leak this design exists to prevent")
    class NoPermitLeak {

        @Test
        @DisplayName("a request refused by a later delegate does not charge an earlier one")
        void refusalChargesNobody() {
            RateLimiter first = tokenBucket(10, 10, Duration.ofSeconds(1));
            RateLimiter second = fixedWindow(1, Duration.ofHours(1));
            RateLimiter limiter = RateLimiters.composite().add(first).add(second).build();

            assertThat(limiter.isAllowed("client")).isTrue();
            long headroomBefore = first.peek("client", 1).remaining();

            // Refused by `second`. Had the composite charged `first` before checking,
            // its headroom would drop and the client would be starved by a limit it
            // never actually tripped.
            assertThat(limiter.isAllowed("client")).isFalse();
            assertThat(first.peek("client", 1).remaining()).isEqualTo(headroomBefore);
        }

        @Test
        @DisplayName("repeated refusals never drain the earlier delegate")
        void repeatedRefusalsDoNotDrain() {
            RateLimiter first = tokenBucket(5, 1, Duration.ofHours(1));
            RateLimiter second = fixedWindow(1, Duration.ofHours(1));
            RateLimiter limiter = RateLimiters.composite().add(first).add(second).build();

            limiter.tryAcquire("client");
            for (int i = 0; i < 20; i++) {
                assertThat(limiter.isAllowed("client")).isFalse();
            }

            assertThat(first.peek("client", 1).remaining()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("peek")
    class Peek {

        @Test
        @DisplayName("consumes from no delegate")
        void peekConsumesNothing() {
            RateLimiter first = tokenBucket(3, 1, Duration.ofHours(1));
            RateLimiter second = tokenBucket(3, 1, Duration.ofHours(1));
            RateLimiter limiter = RateLimiters.composite().add(first).add(second).build();

            assertThat(limiter.peek("client", 1).allowed()).isTrue();
            assertThat(limiter.peek("client", 1).allowed()).isTrue();

            assertThat(first.peek("client", 1).remaining()).isEqualTo(2);
            assertThat(second.peek("client", 1).remaining()).isEqualTo(2);
        }

        @Test
        @DisplayName("reports a refusal when any delegate would refuse")
        void peekReportsRefusal() {
            RateLimiter open = tokenBucket(10, 1, Duration.ofHours(1));
            RateLimiter closed = fixedWindow(1, Duration.ofHours(1));
            RateLimiter limiter = RateLimiters.composite().add(open).add(closed).build();
            limiter.tryAcquire("client");

            assertThat(limiter.peek("client", 1).allowed()).isFalse();
        }

        @Test
        @DisplayName("reports the tightest delegate when all would allow")
        void peekReportsTightest() {
            RateLimiter generous = tokenBucket(100, 1, Duration.ofHours(1));
            RateLimiter tight = tokenBucket(4, 1, Duration.ofHours(1));
            RateLimiter limiter = RateLimiters.composite().add(generous).add(tight).build();

            assertThat(limiter.peek("client", 1).remaining()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("acquire")
    class Acquire {

        @Test
        @DisplayName("returns immediately when the request can be satisfied")
        void returnsImmediatelyWhenAllowed() throws InterruptedException {
            RateLimiter limiter = RateLimiters.composite()
                    .add(tokenBucket(5, 1, Duration.ofHours(1)))
                    .build();

            assertThat(limiter.acquire("client", 1, Duration.ofSeconds(5)).allowed()).isTrue();
        }

        @Test
        @DisplayName("gives up at the deadline rather than blocking forever")
        void givesUpAtTheDeadline() throws InterruptedException {
            RateLimiter limiter = RateLimiters.composite()
                    .add(tokenBucket(1, 1, Duration.ofHours(1)))
                    .build();
            limiter.tryAcquire("client");

            long startedAt = System.nanoTime();
            RateLimitResult result = limiter.acquire("client", 1, Duration.ofMillis(50));
            long elapsedNanos = System.nanoTime() - startedAt;

            assertThat(result.allowed()).isFalse();
            assertThat(elapsedNanos).isLessThan(Duration.ofSeconds(5).toNanos());
        }

        @Test
        @DisplayName("returns at once for a request no waiting could satisfy")
        void doesNotWaitForTheImpossible() throws InterruptedException {
            RateLimiter limiter = RateLimiters.composite()
                    .add(tokenBucket(2, 1, Duration.ofHours(1)))
                    .build();

            long startedAt = System.nanoTime();
            // More permits than the delegate's capacity: no wait would ever help.
            RateLimitResult result = limiter.acquire("client", 3, Duration.ofSeconds(30));
            long elapsedNanos = System.nanoTime() - startedAt;

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfter()).isZero();
            assertThat(elapsedNanos).isLessThan(Duration.ofSeconds(1).toNanos());
        }
    }

    @Nested
    @DisplayName("construction and reset")
    class ConstructionAndReset {

        @Test
        @DisplayName("reset clears every delegate")
        void resetClearsEveryDelegate() {
            RateLimiter first = tokenBucket(1, 1, Duration.ofHours(1));
            RateLimiter second = tokenBucket(1, 1, Duration.ofHours(1));
            RateLimiter limiter = RateLimiters.composite().add(first).add(second).build();

            assertThat(limiter.isAllowed("client")).isTrue();
            assertThat(limiter.isAllowed("client")).isFalse();

            limiter.reset("client");

            assertThat(limiter.isAllowed("client")).isTrue();
        }

        @Test
        @DisplayName("a composite needs at least one delegate")
        void rejectsEmptyComposite() {
            assertThatThrownBy(() -> RateLimiters.composite().build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one");
        }

        @Test
        @DisplayName("a single delegate behaves like that delegate")
        void singleDelegateIsTransparent() {
            RateLimiter limiter = RateLimiters.composite()
                    .add(tokenBucket(2, 1, Duration.ofHours(1)))
                    .build();

            assertThat(limiter.isAllowed("client")).isTrue();
            assertThat(limiter.isAllowed("client")).isTrue();
            assertThat(limiter.isAllowed("client")).isFalse();
        }
    }
}

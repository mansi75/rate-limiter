package com.ratelimit.concurrency;

import com.ratelimit.RateLimiter;
import com.ratelimit.RateLimiters;
import com.ratelimit.time.MutableTimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests that justify the store's design.
 *
 * <p>A rate limiter is only useful if it is exact under contention. A
 * get-modify-put store passes every single-threaded test in
 * {@code TokenBucketLimiterTest} and fails these, because two threads can each
 * read the same remaining count and each conclude they may proceed.
 *
 * <p>Time is frozen throughout. Refill is not what is under test; mutual
 * exclusion is. Freezing the clock means any over-grant is a lock bug and not a
 * scheduling artefact.
 */
class ConcurrentAcquireTest {

    private static final int THREADS = 64;

    @Test
    @Timeout(30)
    @DisplayName("1,000 threads against a 100-permit bucket grant exactly 100")
    void grantsExactlyCapacityUnderContention() throws InterruptedException {
        int attempts = 1_000;
        int capacity = 100;

        RateLimiter limiter = RateLimiters.tokenBucket()
                .capacity(capacity)
                .refill(1, Duration.ofHours(1))   // effectively no refill during the test
                .timeSource(new MutableTimeSource())
                .build();

        AtomicInteger granted = runConcurrently(attempts, () -> limiter.isAllowed("hot-key"));

        assertThat(granted.get())
                .as("a rate limiter that over-grants under contention is not a rate limiter")
                .isEqualTo(capacity);
    }

    @Test
    @Timeout(30)
    @DisplayName("contention on one key does not leak into another")
    void keysRemainIsolatedUnderContention() throws InterruptedException {
        RateLimiter limiter = RateLimiters.tokenBucket()
                .capacity(50)
                .refill(1, Duration.ofHours(1))
                .timeSource(new MutableTimeSource())
                .build();

        AtomicInteger grantedA = runConcurrently(500, () -> limiter.isAllowed("a"));
        AtomicInteger grantedB = runConcurrently(500, () -> limiter.isAllowed("b"));

        assertThat(grantedA.get()).isEqualTo(50);
        assertThat(grantedB.get()).isEqualTo(50);
    }

    @Test
    @Timeout(30)
    @DisplayName("multi-permit requests never grant a partial allocation")
    void multiPermitRequestsAreAtomicUnderContention() throws InterruptedException {
        int capacity = 100;
        int permitsEach = 4;

        RateLimiter limiter = RateLimiters.tokenBucket()
                .capacity(capacity)
                .refill(1, Duration.ofHours(1))
                .timeSource(new MutableTimeSource())
                .build();

        AtomicInteger granted =
                runConcurrently(1_000, () -> limiter.tryAcquire("hot-key", permitsEach).allowed());

        assertThat(granted.get() * permitsEach)
                .as("total permits handed out must not exceed capacity")
                .isEqualTo(capacity);
    }

    /**
     * Releases {@code attempts} tasks simultaneously and counts the successes.
     *
     * <p>The start latch matters. Submitting tasks to a pool staggers them enough
     * that a broken implementation can pass by luck; holding every thread at a
     * barrier and releasing them together is what actually creates contention.
     */
    private static AtomicInteger runConcurrently(int attempts, Attempt attempt)
            throws InterruptedException {

        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (attempt.run()) {
                            granted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(finished.await(20, TimeUnit.SECONDS))
                    .as("all tasks should complete")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
        return granted;
    }

    @FunctionalInterface
    private interface Attempt {
        boolean run();
    }
}

package com.ratelimit.store;

import com.ratelimit.RateLimiter;
import com.ratelimit.RateLimiters;
import com.ratelimit.time.MutableTimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bounding behaviour of the in-memory store.
 *
 * <p>These are memory-safety tests, not feature tests. Rate limiter keys usually
 * come from untrusted input, so a store that grows without limit is a denial of
 * service vector that any client can trigger by varying its key.
 */
class InMemoryStoreTest {

    @Test
    @DisplayName("reclaims keys that have gone idle")
    void expiresIdleKeys() {
        MutableTimeSource time = new MutableTimeSource();
        InMemoryStore store = InMemoryStore.builder()
                .expireIdleAfter(Duration.ofMinutes(1))
                .build();

        RateLimiter limiter = RateLimiters.tokenBucket()
                .capacity(10)
                .refill(1, Duration.ofSeconds(1))
                .store(store)
                .timeSource(time)
                .build();

        for (int i = 0; i < 100; i++) {
            limiter.isAllowed("key-" + i);
        }
        assertThat(store.size()).isEqualTo(100);

        time.advance(Duration.ofMinutes(5));

        // The sweep is sampled rather than run on every call, so drive enough
        // traffic to trigger one.
        for (int i = 0; i < 2_000; i++) {
            limiter.isAllowed("survivor");
        }

        assertThat(store.size())
                .as("idle keys should have been reclaimed")
                .isLessThan(100);
    }

    @Test
    @DisplayName("stays within its key ceiling under a flood of distinct keys")
    void boundsKeyCount() {
        InMemoryStore store = InMemoryStore.builder()
                .maxKeys(100)
                .expireIdleAfter(Duration.ofHours(1))
                .evictionPolicy(EvictionPolicy.LEAST_RECENTLY_USED)
                .build();

        RateLimiter limiter = RateLimiters.tokenBucket()
                .capacity(10)
                .refill(1, Duration.ofSeconds(1))
                .store(store)
                .timeSource(new MutableTimeSource())
                .build();

        for (int i = 0; i < 10_000; i++) {
            limiter.isAllowed("key-" + i);
        }

        assertThat(store.size())
                .as("an unbounded map here is a memory leak any caller could trigger")
                .isLessThanOrEqualTo(100 + 1024);
    }

    @Test
    @DisplayName("rejects a non-positive key ceiling")
    void rejectsInvalidMaxKeys() {
        assertThatThrownBy(() -> InMemoryStore.builder().maxKeys(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxKeys must be positive");
    }

    @Test
    @DisplayName("clear discards everything")
    void clearRemovesAllKeys() {
        InMemoryStore store = InMemoryStore.withDefaults();
        RateLimiter limiter = RateLimiters.tokenBucket()
                .capacity(1)
                .refill(1, Duration.ofSeconds(1))
                .store(store)
                .build();

        limiter.isAllowed("a");
        limiter.isAllowed("b");
        assertThat(store.size()).isEqualTo(2);

        store.clear();

        assertThat(store.size()).isZero();
    }
}

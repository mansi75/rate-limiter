package com.ratelimit.rule;

import com.ratelimit.RateLimiter;
import com.ratelimit.RateLimiters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMatcherTest {

    @Nested
    @DisplayName("the built-in matchers")
    class BuiltIns {

        @Test
        void prefixMatchesTheStartOfAKey() {
            KeyMatcher matcher = KeyMatcher.prefix("premium:");

            assertThat(matcher.test("premium:acme")).isTrue();
            assertThat(matcher.test("premium:")).isTrue();
            assertThat(matcher.test("free:acme")).isFalse();
            assertThat(matcher.test("not-premium:acme")).isFalse();
        }

        @Test
        void exactMatchesOneKeyOnly() {
            KeyMatcher matcher = KeyMatcher.exact("user:42");

            assertThat(matcher.test("user:42")).isTrue();
            assertThat(matcher.test("user:420")).isFalse();
            assertThat(matcher.test("user:4")).isFalse();
        }

        @Test
        @DisplayName("regex must match the whole key, not merely appear in it")
        void regexMatchesTheWholeKey() {
            KeyMatcher matcher = KeyMatcher.regex("^ip:10\\..*");

            assertThat(matcher.test("ip:10.0.0.1")).isTrue();
            assertThat(matcher.test("ip:11.0.0.1")).isFalse();
            assertThat(matcher.test("prefix-ip:10.0.0.1")).isFalse();
        }

        @Test
        void anyMatchesEverything() {
            assertThat(KeyMatcher.any().test("")).isTrue();
            assertThat(KeyMatcher.any().test("whatever")).isTrue();
        }

        @Test
        void rejectsNullArguments() {
            assertThatThrownBy(() -> KeyMatcher.prefix(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> KeyMatcher.exact(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> KeyMatcher.regex(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("composition")
    class Composition {

        @Test
        void andRequiresBoth() {
            KeyMatcher matcher = KeyMatcher.prefix("a").and(KeyMatcher.prefix("ab"));

            assertThat(matcher.test("abc")).isTrue();
            assertThat(matcher.test("acc")).isFalse();
        }

        @Test
        void orAcceptsEither() {
            KeyMatcher matcher = KeyMatcher.exact("a").or(KeyMatcher.exact("b"));

            assertThat(matcher.test("a")).isTrue();
            assertThat(matcher.test("b")).isTrue();
            assertThat(matcher.test("c")).isFalse();
        }

        @Test
        void negateInverts() {
            KeyMatcher matcher = KeyMatcher.prefix("internal:").negate();

            assertThat(matcher.test("public:x")).isTrue();
            assertThat(matcher.test("internal:x")).isFalse();
        }

        @Test
        @DisplayName("a composed matcher is still a KeyMatcher, so rules().when() accepts it")
        void compositionStaysAKeyMatcher() {
            // Inherited from Predicate unchanged, these return Predicate<String> and
            // this does not compile. The narrowing overrides are what make the
            // documented "and, or and negate work for free" true in practice.
            KeyMatcher internalButNotTest = KeyMatcher.prefix("internal:")
                    .and(KeyMatcher.prefix("internal:test:").negate());

            RateLimiter strict = RateLimiters.tokenBucket()
                    .capacity(1).refill(1, Duration.ofHours(1)).build();

            RateLimiter limiter = RateLimiters.rules()
                    .when(internalButNotTest).unlimited()
                    .otherwise(strict)
                    .build();

            for (int i = 0; i < 20; i++) {
                assertThat(limiter.isAllowed("internal:job")).isTrue();
            }
            assertThat(limiter.isAllowed("internal:test:job")).isTrue();
            assertThat(limiter.isAllowed("internal:test:job")).isFalse();
        }

        @Test
        @DisplayName("composes with a plain lambda on either side")
        void composesWithPlainPredicates() {
            KeyMatcher matcher = KeyMatcher.prefix("user:").and(key -> key.length() > 6);

            assertThat(matcher.test("user:42")).isTrue();
            assertThat(matcher.test("user:")).isFalse();
        }

        @Test
        void rejectsNullOperands() {
            assertThatThrownBy(() -> KeyMatcher.any().and(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> KeyMatcher.any().or(null)).isInstanceOf(NullPointerException.class);
        }
    }
}

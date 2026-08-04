package com.ratelimit.builder;

import com.ratelimit.RateLimiter;
import com.ratelimit.algorithm.Algorithms;
import com.ratelimit.algorithm.UnlimitedRateLimiter;
import com.ratelimit.rule.KeyMatcher;
import com.ratelimit.rule.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a limiter that routes keys to different limits.
 *
 * <pre>{@code
 * RateLimiter limiter = RateLimiters.rules()
 *         .when(KeyMatcher.prefix("internal:")).unlimited()
 *         .when(KeyMatcher.prefix("premium:")).use(generousLimiter)
 *         .otherwise(strictLimiter)
 *         .build();
 * }</pre>
 *
 * <h2>Why {@code when} returns a different type</h2>
 *
 * <p>{@link #when(KeyMatcher)} returns a {@link Stage}, which offers only
 * {@code use} and {@code unlimited}. This makes it a compile error to write a
 * matcher and forget to say what limit applies to it. The alternative, a single
 * {@code rule(matcher, limiter)} method, is simpler but lets a caller build a
 * half-specified rule set that only fails at run time.
 */
public final class RuleSetBuilder {

    private final List<Rule> rules = new ArrayList<>();
    private RateLimiter fallback;

    /** Use {@link com.ratelimit.RateLimiters#rules()}. */
    public RuleSetBuilder() {
    }

    /**
     * Begins a rule. Rules are evaluated in declaration order; the first match
     * wins.
     *
     * @param matcher which keys the rule governs
     * @return a stage that must be completed with a limiter
     * @throws NullPointerException if {@code matcher} is null
     */
    public Stage when(KeyMatcher matcher) {
        return new Stage(Objects.requireNonNull(matcher, "matcher"));
    }

    /**
     * Sets the limit for keys no rule matched. Required.
     *
     * @param limiter the default limiter
     * @return this builder
     * @throws NullPointerException if {@code limiter} is null
     */
    public RuleSetBuilder otherwise(RateLimiter limiter) {
        this.fallback = Objects.requireNonNull(limiter, "limiter");
        return this;
    }

    /** Sets an unlimited default. Equivalent to {@code otherwise(unlimited)}. */
    public RuleSetBuilder otherwiseUnlimited() {
        return otherwise(UnlimitedRateLimiter.INSTANCE);
    }

    /**
     * @return the rule-based limiter
     * @throws IllegalArgumentException if no default was set
     */
    public RateLimiter build() {
        if (fallback == null) {
            throw new IllegalArgumentException(
                    "otherwise(...) is required: every key must resolve to a limit");
        }
        return Algorithms.ruleBased(rules, fallback);
    }

    /** A matcher awaiting the limiter that applies to it. */
    public final class Stage {

        private final KeyMatcher matcher;

        private Stage(KeyMatcher matcher) {
            this.matcher = matcher;
        }

        /**
         * @param limiter the limit for keys this rule matches
         * @return the enclosing builder
         * @throws NullPointerException if {@code limiter} is null
         */
        public RuleSetBuilder use(RateLimiter limiter) {
            rules.add(new Rule(matcher, Objects.requireNonNull(limiter, "limiter")));
            return RuleSetBuilder.this;
        }

        /**
         * Exempts keys this rule matches from limiting entirely.
         *
         * @return the enclosing builder
         */
        public RuleSetBuilder unlimited() {
            return use(UnlimitedRateLimiter.INSTANCE);
        }
    }
}

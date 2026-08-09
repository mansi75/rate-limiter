package com.ratelimit.rule;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Selects which keys a rule applies to.
 *
 * <p>Extends {@link Predicate} so callers can use {@code and}, {@code or}, and
 * {@code negate} for free, and can pass a plain lambda anywhere a matcher is
 * expected.
 *
 * <p>Those three are overridden below purely to narrow the return type. Inherited
 * unchanged they hand back a {@code Predicate<String>}, which will not compile
 * where a {@code KeyMatcher} is required — so a composed matcher could not be
 * passed to {@code rules().when(...)}, which is the one place matchers are used.
 */
@FunctionalInterface
public interface KeyMatcher extends Predicate<String> {

    /**
     * @param prefix the required prefix
     * @return a matcher accepting keys starting with {@code prefix}
     */
    static KeyMatcher prefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return key -> key.startsWith(prefix);
    }

    /**
     * @param key the key to match
     * @return a matcher accepting exactly that key
     */
    static KeyMatcher exact(String key) {
        Objects.requireNonNull(key, "key");
        return key::equals;
    }

    /**
     * The pattern is compiled once, here, rather than on every decision.
     *
     * @param regex the pattern
     * @return a matcher accepting keys the pattern fully matches
     */
    static KeyMatcher regex(String regex) {
        Pattern compiled = Pattern.compile(Objects.requireNonNull(regex, "regex"));
        return key -> compiled.matcher(key).matches();
    }

    /** @return a matcher accepting every key */
    static KeyMatcher any() {
        return key -> true;
    }

    /**
     * @param other the matcher that must also accept
     * @return a matcher accepting keys both accept
     */
    @Override
    default KeyMatcher and(Predicate<? super String> other) {
        Objects.requireNonNull(other, "other");
        return key -> test(key) && other.test(key);
    }

    /**
     * @param other the alternative matcher
     * @return a matcher accepting keys either accepts
     */
    @Override
    default KeyMatcher or(Predicate<? super String> other) {
        Objects.requireNonNull(other, "other");
        return key -> test(key) || other.test(key);
    }

    /** @return a matcher accepting exactly the keys this one rejects */
    @Override
    default KeyMatcher negate() {
        return key -> !test(key);
    }
}

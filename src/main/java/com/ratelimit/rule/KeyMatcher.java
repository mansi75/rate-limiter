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
}

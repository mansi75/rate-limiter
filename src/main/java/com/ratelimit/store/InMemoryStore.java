package com.ratelimit.store;

import com.ratelimit.RateLimitResult;
import com.ratelimit.state.LimiterState;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

/**
 * A {@link LimiterStore} backed by a bounded {@link ConcurrentHashMap}.
 *
 * <h2>How atomicity is achieved</h2>
 *
 * <p>Without a lock table, and without locking the whole map. {@link
 * ConcurrentHashMap#compute} holds the bin lock for the key across the whole
 * remapping function, so the read, the algorithm, and the write happen with no
 * other thread able to touch that key. Threads working on different keys land in
 * different bins and never contend.
 *
 * <p>This is why the {@link StateMutation} contract forbids blocking. A slow
 * mutation would hold a bin lock, and every other key that hashes to the same bin
 * would stall behind it.
 *
 * <h2>Bounding</h2>
 *
 * <p>The map is capped at {@code maxKeys} and sweeps idle entries. Both matter:
 * the cap defends against a burst of distinct keys, and the idle sweep reclaims
 * memory in the ordinary case where a key is simply never seen again.
 *
 * <p>This class is thread-safe.
 */
public final class InMemoryStore implements LimiterStore {

    private static final int DEFAULT_MAX_KEYS = 100_000;
    private static final Duration DEFAULT_IDLE = Duration.ofMinutes(10);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final int maxKeys;
    private final long idleNanos;
    private final EvictionPolicy evictionPolicy;
    private final AtomicLong sweepCounter = new AtomicLong();

    private InMemoryStore(Builder builder) {
        this.maxKeys = builder.maxKeys;
        this.idleNanos = builder.idle.toNanos();
        this.evictionPolicy = builder.evictionPolicy;
    }

    /**
     * @return a store holding up to 100,000 keys, expiring entries idle for ten
     *         minutes, evicting least recently used
     */
    public static InMemoryStore withDefaults() {
        return builder().build();
    }

    /** @return a builder for a customised store */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends LimiterState> RateLimitResult compute(
            String key,
            LongFunction<S> stateFactory,
            StateMutation<S> mutation,
            long nowNanos,
            int permits) {

        Objects.requireNonNull(key, "key");
        maybeSweep(nowNanos);

        // A one-element array is the standard way to get a value out of the
        // remapping function, which can only return the new map value.
        RateLimitResult[] outcome = new RateLimitResult[1];

        entries.compute(key, (ignored, existing) -> {
            Entry entry = (existing != null) ? existing : new Entry(stateFactory.apply(nowNanos));
            outcome[0] = mutation.apply((S) entry.state, nowNanos, permits);
            entry.lastAccessNanos = nowNanos;
            entry.accessCount++;
            return entry;
        });

        return outcome[0];
    }

    @Override
    public void reset(String key) {
        entries.remove(Objects.requireNonNull(key, "key"));
    }

    @Override
    public void clear() {
        entries.clear();
    }

    @Override
    public int size() {
        return entries.size();
    }

    /**
     * Sweeps idle entries, and evicts if still over capacity.
     *
     * <p>Sampled rather than run on every call: a sweep walks the whole map, and
     * doing that on every decision would make the store O(n) per request. Once
     * every 1024 decisions keeps the amortised cost negligible while still
     * reclaiming memory promptly under load.
     */
    private void maybeSweep(long nowNanos) {
        if ((sweepCounter.incrementAndGet() & 0x3FF) != 0) {
            return;
        }
        entries.entrySet().removeIf(e -> nowNanos - e.getValue().lastAccessNanos > idleNanos);

        if (evictionPolicy == EvictionPolicy.NONE || entries.size() <= maxKeys) {
            return;
        }
        Comparator<Map.Entry<String, Entry>> order =
                (evictionPolicy == EvictionPolicy.LEAST_FREQUENTLY_USED)
                        ? Comparator.comparingLong(e -> e.getValue().accessCount)
                        : Comparator.comparingLong(e -> e.getValue().lastAccessNanos);

        entries.entrySet().stream()
                .sorted(order)
                .limit(Math.max(0, entries.size() - maxKeys))
                .map(Map.Entry::getKey)
                .forEach(entries::remove);
    }

    /** Map value: the algorithm state plus the bookkeeping eviction needs. */
    private static final class Entry {
        private final LimiterState state;
        private long lastAccessNanos;
        private long accessCount;

        private Entry(LimiterState state) {
            this.state = state;
        }
    }

    /** Fluent builder for {@link InMemoryStore}. */
    public static final class Builder {
        private int maxKeys = DEFAULT_MAX_KEYS;
        private Duration idle = DEFAULT_IDLE;
        private EvictionPolicy evictionPolicy = EvictionPolicy.LEAST_RECENTLY_USED;

        private Builder() {
        }

        /**
         * @param maxKeys ceiling on retained keys; must be positive
         * @return this builder
         * @throws IllegalArgumentException if {@code maxKeys} is not positive
         */
        public Builder maxKeys(int maxKeys) {
            if (maxKeys <= 0) {
                throw new IllegalArgumentException("maxKeys must be positive: " + maxKeys);
            }
            this.maxKeys = maxKeys;
            return this;
        }

        /**
         * @param idle how long a key may go unused before it is reclaimed
         * @return this builder
         * @throws IllegalArgumentException if {@code idle} is negative or zero
         */
        public Builder expireIdleAfter(Duration idle) {
            Objects.requireNonNull(idle, "idle");
            if (idle.isNegative() || idle.isZero()) {
                throw new IllegalArgumentException("idle must be positive: " + idle);
            }
            this.idle = idle;
            return this;
        }

        /**
         * @param policy which key to discard at capacity
         * @return this builder
         */
        public Builder evictionPolicy(EvictionPolicy policy) {
            this.evictionPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** @return the configured store */
        public InMemoryStore build() {
            return new InMemoryStore(this);
        }
    }
}

# Rate Limiter

[![Maven Central](https://img.shields.io/maven-central/v/com.ratelimit/rate-limiter?style=flat-square)](https://central.sonatype.com/artifact/com.ratelimit/rate-limiter)
[![CI](https://img.shields.io/github/actions/workflow/status/mansi/rate-limiter/ci.yml?branch=main&style=flat-square)](https://github.com/mansi/rate-limiter/actions)
[![javadoc](https://img.shields.io/badge/javadoc-latest-blue?style=flat-square)](https://javadoc.io/doc/com.ratelimit/rate-limiter)
[![License](https://img.shields.io/github/license/mansi/rate-limiter?style=flat-square)](LICENSE)

Six rate limiting algorithms behind one interface, with no runtime dependencies and no background threads.

```java
RateLimiter limiter = RateLimiters.tokenBucket()
        .capacity(100)
        .refill(10, Duration.ofSeconds(1))
        .build();

if (limiter.isAllowed("user:42")) {
    handle(request);
}
```

- [Features](#-features)
- [Getting Started](#-getting-started)
  * [Gradle](#gradle)
  * [Maven](#maven)
  * [Packages](#packages)
- [Usage](#-usage)
  * [Basic](#basic)
  * [Reading the result](#reading-the-result)
  * [Several limits at once](#several-limits-at-once)
  * [Different limits per key](#different-limits-per-key)
  * [Waiting instead of failing](#waiting-instead-of-failing)
- [Algorithms](#-algorithms)
- [Options](#-options)
- [Methods](#-methods)
- [Listeners](#-listeners)
- [Types](#-types)
- [Stores](#-stores)
- [Testing](#-testing)
- [Bug Reporting](#-bug-reporting)
- [Feature Request](#-feature-request)
- [Release Notes](#-release-notes)
- [License](#-license)

## 🚀 Features

- 🏎️ **Zero Dependencies** — nothing enters your classpath but this
- 🧩 **Six Algorithms** — token bucket, leaky bucket, fixed window, sliding window log, sliding window counter, GCRA
- 🔀 **Composable** — combine limits, or route keys to different limits by pattern
- 🧵 **Thread-safe** — exact under contention, verified by tests that release 1,000 threads at once
- 🧊 **No background threads** — state refills lazily from elapsed time
- 🌐 **HTTP-ready** — every result maps straight onto `X-RateLimit-*` and `Retry-After`
- 🔌 **Pluggable storage** — one SPI to implement for a distributed backend
- ⏱️ **Testable** — inject a controllable clock and test your own limits without sleeping
- 📊 **Observable** — listener hooks for metrics, with no metrics dependency

## 📦 Getting Started

### Gradle

```kotlin
dependencies {
    implementation("com.ratelimit:rate-limiter:0.1.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.ratelimit</groupId>
    <artifactId>rate-limiter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Requires Java 17 or later.

### Packages

Everything you need day to day is in `com.ratelimit`. The rest is split by role, so an import
tells you what kind of thing you are looking at:

| Package | What lives there |
| --- | --- |
| `com.ratelimit` | `RateLimiter`, `RateLimiters`, `RateLimitResult`, `RateLimitExceededException` |
| `com.ratelimit.rule` | `KeyMatcher`, `Rule` — routing keys to different limits |
| `com.ratelimit.time` | `TimeSource`, `MutableTimeSource` — the injectable clock |
| `com.ratelimit.store` | `LimiterStore`, `InMemoryStore`, `EvictionPolicy`, `StateMutation` |
| `com.ratelimit.listener` | `RateLimitListener`, `CompositeListener` |
| `com.ratelimit.builder` | the builders `RateLimiters` hands back |
| `com.ratelimit.algorithm` | the algorithm implementations themselves |
| `com.ratelimit.state` | per-key state, of interest only when writing a store |

You reach `com.ratelimit.builder` through `RateLimiters`, not by importing it — the factory
methods return the right builder and the chain flows from there.

## 🔨 Usage

### Basic

```java
import com.ratelimit.RateLimiter;
import com.ratelimit.RateLimiters;

RateLimiter limiter = RateLimiters.tokenBucket()
        .capacity(100)
        .refill(10, Duration.ofSeconds(1))
        .build();

if (limiter.isAllowed("user:42")) {
    handle(request);
}
```

The key is opaque. Namespace it by whatever dimension you are limiting: `"ip:203.0.113.9"`, `"user:42:search"`, `"tenant:acme"`. State is created on first use and reclaimed when a key goes idle.

### Reading the result

```java
RateLimitResult result = limiter.tryAcquire("user:42");

if (!result.allowed()) {
    response.setStatus(429);
    result.asHeaders().forEach(response::setHeader);
    return;
}
```

Or throw, when rejection is exceptional:

```java
limiter.require("user:42");   // throws RateLimitExceededException
```

### Several limits at once

A burst limit and a sustained limit, both enforced:

```java
RateLimiter limiter = RateLimiters.composite()
        .add(RateLimiters.tokenBucket()
                .capacity(20).refill(10, Duration.ofSeconds(1)).build())
        .add(RateLimiters.slidingWindowCounter()
                .limit(1_000).window(Duration.ofHours(1)).build())
        .build();
```

The composite checks every delegate before committing to any, so a request refused by the second limit does not silently consume a permit from the first.

### Different limits per key

```java
import com.ratelimit.rule.KeyMatcher;

RateLimiter limiter = RateLimiters.rules()
        .when(KeyMatcher.prefix("internal:")).unlimited()
        .when(KeyMatcher.prefix("premium:")).use(generousLimiter)
        .otherwise(strictLimiter)
        .build();
```

Rules are evaluated in declaration order and the first match wins. `otherwise` is required: every key must resolve to a limit.

### Waiting instead of failing

```java
RateLimitResult result = limiter.acquire("worker:1", 1, Duration.ofSeconds(5));
```

Sleeps for exactly as long as the algorithm says is needed, re-checks, and gives up at the deadline. Useful for background work that should be paced rather than dropped.

## 🎛 Algorithms

| Algorithm | State per key | Accuracy | Bursts | Use when |
| --- | --- | --- | --- | --- |
| `tokenBucket()` | 2 longs | exact | allowed up to capacity | default choice; bursts are harmless |
| `leakyBucket()` | 2 longs | exact | refused | downstream needs a steady rate |
| `fixedWindow()` | 2 longs | **permits 2× at boundaries** | at boundaries | only when its cheapness outweighs its inaccuracy |
| `slidingWindowLog()` | O(limit) longs | exact | none | small limits where precision matters |
| `slidingWindowCounter()` | 3 longs | approximate, bounded error | none | best default of the window algorithms |
| `gcra()` | 1 long | exact | configurable | cheapest; what most production limiters run |

`fixedWindow()` is included deliberately. It is the most commonly implemented rate limiter and it permits twice the configured limit across a window boundary — 100 requests at 11:00:59 and 100 more at 11:01:00. `FixedWindowBoundaryTest` drives exactly that scenario and asserts the over-grant, then runs it through `slidingWindowCounter()` and asserts the refusal.

## ⚙️ Options

Every builder accepts the three options below, plus its own.

### `store`

Type: `com.ratelimit.store.LimiterStore`
Default: `InMemoryStore.withDefaults()`

Where per-key state lives. See [Stores](#-stores).

### `timeSource`

Type: `com.ratelimit.time.TimeSource`
Default: `TimeSource.system()`

The clock. Pass a `MutableTimeSource` in tests. Deliberately not `java.time.Clock` — see [Testing](#-testing).

### `listener`

Type: `com.ratelimit.listener.RateLimitListener`
Default: `RateLimitListener.NOOP`

Notified of every decision. See [Listeners](#-listeners).

---

### `tokenBucket()`

#### `capacity`

Type: `long` · Required

The largest burst permitted. Independent of the refill rate.

#### `refill`

Type: `(long permits, Duration per)` · Required

The long-run average rate. `refill(10, Duration.ofSeconds(1))` grants ten permits per second.

#### `initiallyFull`

Type: `boolean` · Default: `true`

Whether a key starts with a full bucket. `true` is almost always right — a client never seen before has consumed nothing. Set `false` to make new keys warm up.

---

### `leakyBucket()`

#### `capacity`

Type: `long` · Required

Queue depth: how many requests may be waiting before new ones are refused.

#### `leak`

Type: `(long permits, Duration per)` · Required

The constant drain rate.

---

### `fixedWindow()`, `slidingWindowLog()`, `slidingWindowCounter()`

#### `limit`

Type: `long` · Required

Permits allowed per window.

#### `window`

Type: `Duration` · Required

The window length.

---

### `gcra()`

#### `rate`

Type: `(long permits, Duration per)` · Required

What the caller may sustain indefinitely.

#### `burst`

Type: `long` · Default: `1`

How far ahead of the sustained rate a caller may momentarily run. A burst of `1` produces perfectly even spacing.

---

### `composite()`

#### `add`

Type: `RateLimiter` · At least one required

A limiter that must also permit the request. Order cheapest first.

---

### `rules()`

#### `when(matcher).use(limiter)`

Adds a rule. Evaluated in declaration order.

#### `when(matcher).unlimited()`

Exempts matching keys entirely.

#### `otherwise(limiter)` / `otherwiseUnlimited()`

The default for keys no rule matched. Required.

## 🔧 Methods

### `tryAcquire(key)`

Returns: `RateLimitResult`

Takes one permit if available. Never blocks.

### `tryAcquire(key, permits)`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `key` | `String` | required | subject of the limit |
| `permits` | `int` | required | how many to take, all or nothing |

Returns: `RateLimitResult`

A request for more permits than the limiter's capacity is refused immediately with a zero retry delay, rather than reporting a wait that will never end.

### `peek(key, permits)`

Returns: `RateLimitResult`

What `tryAcquire` would return, consuming nothing. A snapshot; may be stale under concurrency.

### `acquire(key, permits, timeout)`

Returns: `RateLimitResult` · Throws: `InterruptedException`

Waits up to `timeout` for permits, sleeping for the reported retry delay rather than spinning.

### `isAllowed(key)`

Returns: `boolean`

Convenience form of `tryAcquire(key)`.

### `require(key)`

Throws: `RateLimitExceededException`

For a service boundary where rejection is exceptional. The exception carries the key and the full result.

### `reset(key)`

Discards all state for a key, as if it had never been seen.

## 🖱 Listeners

```java
import com.ratelimit.listener.RateLimitListener;

RateLimiter limiter = RateLimiters.tokenBucket()
        .capacity(100)
        .refill(10, Duration.ofSeconds(1))
        .listener(new RateLimitListener() {
            @Override
            public void onRejected(String key, RateLimitResult result) {
                meterRegistry.counter("ratelimit.rejected").increment();
            }
        })
        .build();
```

### `onAllowed(key, result)`

Called after permits were granted.

### `onRejected(key, result)`

Called after permits were refused.

### `onReset(key)`

Called after a key's state was discarded.

Every method has a default no-op body, so implement only what you need. Callbacks run inline on the calling thread after the store lock is released — a slow listener slows down the caller. Use `CompositeListener.of(...)` to fan out to several; it swallows exceptions so that a broken metrics sink cannot break the request path.

## 💎 Types

### `RateLimitResult`

`com.ratelimit.RateLimitResult`

```java
record RateLimitResult(
    boolean  allowed,      // whether permits were granted
    long     limit,        // configured ceiling
    long     remaining,    // permits left; never negative
    Duration retryAfter    // ZERO when allowed
)
```

`asHeaders()` renders it as `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and — on rejection only — `Retry-After` in whole seconds rounded up, so a client that waits exactly that long will succeed.

### `KeyMatcher`

`com.ratelimit.rule.KeyMatcher`

Extends `Predicate<String>`, so `and`, `or`, and `negate` work for free.

```java
KeyMatcher.prefix("premium:")
KeyMatcher.exact("user:42")
KeyMatcher.regex("^ip:10\\..*")   // compiled once, not per decision
KeyMatcher.any()
```

### `EvictionPolicy`

`com.ratelimit.store.EvictionPolicy`

`LEAST_RECENTLY_USED` · `LEAST_FREQUENTLY_USED` · `NONE`

### `RateLimitExceededException`

`com.ratelimit.RateLimitExceededException`

Unchecked. Carries `key()` and `result()`.

## 💾 Stores

State lives behind one interface, `com.ratelimit.store.LimiterStore`:

```java
package com.ratelimit.store;

public interface LimiterStore {
    <S extends LimiterState> RateLimitResult compute(
            String key,
            LongFunction<S> stateFactory,
            StateMutation<S> mutation,
            long nowNanos,
            int permits);

    void reset(String key);
    void clear();
    int size();
}
```

There is no `get` and no `put`, on purpose. A rate limiting decision is a read-modify-write, and splitting it into two calls lets two threads each read the same remaining permit and each conclude they may proceed. The single `compute` method makes that mistake unrepresentable.

`InMemoryStore` implements it with `ConcurrentHashMap.compute`, which holds the bin lock across the whole remapping function — per-key exclusion with no lock table and no global lock.

```java
import com.ratelimit.store.EvictionPolicy;
import com.ratelimit.store.InMemoryStore;

RateLimiters.tokenBucket()
        .capacity(100)
        .refill(10, Duration.ofSeconds(1))
        .store(InMemoryStore.builder()
                .maxKeys(10_000)
                .expireIdleAfter(Duration.ofMinutes(5))
                .evictionPolicy(EvictionPolicy.LEAST_RECENTLY_USED)
                .build())
        .build();
```

The store is bounded by default. Rate limiter keys usually come from untrusted input, so an unbounded map is a memory leak that any client can trigger by varying its key.

### Implementing a distributed store

A distributed store cannot satisfy the atomicity requirement with client-side logic, because the read and the write are separate round trips. It has to push the mutation to the server: a Redis implementation encodes the algorithm as a Lua script and invokes it with `EVALSHA`, so the read, decision, and write happen inside one single-threaded server execution.

## 🧪 Testing

The clock is injectable, so tests never sleep:

```java
import com.ratelimit.time.MutableTimeSource;

MutableTimeSource time = new MutableTimeSource();

RateLimiter limiter = RateLimiters.tokenBucket()
        .capacity(1)
        .refill(1, Duration.ofSeconds(1))
        .timeSource(time)
        .build();

assertThat(limiter.isAllowed("k")).isTrue();
assertThat(limiter.isAllowed("k")).isFalse();

time.advance(Duration.ofSeconds(1));

assertThat(limiter.isAllowed("k")).isTrue();
```

`MutableTimeSource` ships in the main artifact, not the test artifact, so your own code can be tested against a limit the same way.

`TimeSource` deliberately does not use `java.time.Clock`. `Clock` exposes wall-clock time, which jumps backwards when the system clock is corrected by NTP or a VM migration, and a backwards jump would let a caller replay a window that has already elapsed. Every `TimeSource` must be monotonic.

```bash
./gradlew test
```

## 🐛 Bug Reporting

Please [open an issue](https://github.com/mansi/rate-limiter/issues) with the algorithm, the configuration, and a failing case if you have one.

## ⭐ Feature Request

[Open an issue](https://github.com/mansi/rate-limiter/issues) describing what you are trying to limit and why the current options do not express it.

## 📋 Release Notes

See [Releases](https://github.com/mansi/rate-limiter/releases).

## 📜 License

[MIT](LICENSE).

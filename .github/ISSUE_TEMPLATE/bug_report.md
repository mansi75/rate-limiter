---
name: Bug report
about: A limiter is not behaving as documented
title: ''
labels: bug
assignees: ''
---

## What happened

<!-- What you observed. -->

## What you expected

<!-- What the README or javadoc led you to expect. -->

## Configuration

```java
// The builder chain, please — it is usually enough to reproduce the problem.
RateLimiter limiter = RateLimiters.tokenBucket()
        .capacity(100)
        .refill(10, Duration.ofSeconds(1))
        .build();
```

## Reproduction

<!--
The fastest possible route to a fix is a failing test using MutableTimeSource,
because it pins the exact moment things diverge and needs no sleeping:

    MutableTimeSource time = new MutableTimeSource();
    RateLimiter limiter = RateLimiters.tokenBucket()
            .capacity(1).refill(1, Duration.ofSeconds(1)).timeSource(time).build();

    assertThat(limiter.isAllowed("k")).isTrue();
    time.advance(Duration.ofSeconds(1));
    assertThat(limiter.isAllowed("k")).isTrue();   // <-- fails here

If that is not practical, describe the sequence of calls and the timing between them.
-->

## Environment

- rate-limiter version:
- Java version:
- Store: <!-- InMemoryStore with defaults, custom bounds, or your own LimiterStore -->
- Concurrency: <!-- single-threaded, or how many threads -->

## Before filing

<!-- Please check these first; each is documented behaviour rather than a bug. -->

- [ ] If this is `fixedWindow()` allowing 2× the limit around a boundary, that is inherent to the algorithm and documented — `slidingWindowCounter()` is the fix.
- [ ] If limits are being exceeded across multiple JVMs, note that `InMemoryStore` bounds one JVM only.

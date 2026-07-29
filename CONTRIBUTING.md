# Contributing

Thanks for taking the time. This document covers how to build the project, what a change is expected to come with, and the few conventions that are not obvious from reading the code.

## Getting set up

You need JDK 17 or later. Nothing else — the build downloads its own tooling and the library itself has no dependencies.

```bash
./gradlew build      # compile, test, javadoc
./gradlew test       # tests only
./gradlew javadoc    # docs only; warnings are errors
```

## The one rule that is not negotiable

**The published artifact has zero runtime dependencies.**

That is the first line of the README and the main reason someone picks this library over a larger one. A `testImplementation` dependency is fine. An `implementation` or `api` dependency is not, no matter how small or how widely used it already is — pulling in a logging facade or an annotations jar puts it on the classpath of every consumer, including ones already fighting a version conflict over it.

If you find yourself wanting a dependency, the answer is usually an SPI: define the interface here, let the caller supply the implementation. That is exactly what `LimiterStore` and `RateLimitListener` are for.

## What a change should come with

- **A test that fails without it.** For a bug fix, write the failing test first and put the reproduction in the test name.
- **No sleeping in tests.** Inject a `MutableTimeSource` and advance it. A test that calls `Thread.sleep` is slow, flaky on a loaded CI box, and usually not testing what it claims to. The only exception is a test of `acquire(...)`, which waits against the system clock by design.
- **Javadoc on anything public.** Say what the option means and when you would reach for it, not just what type it is. `Xdoclint` runs as part of the build.
- **A CHANGELOG entry** under `Unreleased`, if the change is visible to a caller.

## Conventions worth knowing

**Comments explain why, not what.** The code already says what it does. A comment earns its place by recording the thing the next reader would otherwise have to rediscover — why the window is aligned to the clock origin rather than to first use, why the fractional refill remainder is left on the clock, why `Retry-After` rounds up. If a comment restates the line below it, delete it.

**Time is monotonic and injected.** Never call `System.nanoTime()` or `Instant.now()` inside an algorithm. Take the clock reading once, at the entry point, and thread it through — two reads inside one decision can disagree, and that disagreement becomes a permit granted twice.

**Rate maths is exact long arithmetic.** Rates are kept as a `(permits, periodNanos)` pair rather than collapsed to nanos-per-permit, because that division is lossy for rates like three per second and the error compounds over every refill. Products saturate instead of wrapping. If you are reaching for `double` in an algorithm, something has gone wrong.

**A decision is one read-modify-write.** All state access goes through `LimiterStore.compute`. There is deliberately no `get` and no `put`: splitting a decision into two calls lets two threads each read the same last permit and each conclude they may proceed.

## Adding an algorithm

1. State in `state/`, implementing `LimiterState`. Keep it small — it exists once per live key.
2. The limiter in `algorithm/`, extending `AbstractRateLimiter`, implementing `newState` and `evaluate`.
3. A builder in `builder/`, extending `AbstractLimiterBuilder`.
4. A factory method on `RateLimiters`.
5. Tests covering: the limit holds, permits come back as time passes, `retryAfter` is exact enough that a client honouring it succeeds, an oversized request is refused with a zero delay, and keys stay independent.
6. A row in the README's algorithm table, honest about the trade-off. `fixedWindow()` documents that it over-grants at boundaries; yours should be equally candid.

## Adding a store

Implement `LimiterStore`. The whole contract is that `compute` is atomic per key.

A distributed store cannot satisfy that with client-side logic, because the read and the write are separate round trips. It has to push the mutation to the server — a Redis implementation encodes the algorithm as a Lua script and invokes it with `EVALSHA`, so the read, decision and write happen inside one single-threaded server execution. If your design has a read, then a decision in Java, then a write, it is racy, and the race only shows up under the load you built it for.

## Pull requests

Keep them focused; one concern per PR. Describe what changes for a caller, and say explicitly if anything about existing behaviour changes — this library is a dependency, and a surprise is worse than a missing feature.

Before pushing:

```bash
./gradlew build
```

## Reporting bugs

Open an issue with the algorithm, the configuration, and a failing case if you have one. A `MutableTimeSource`-based reproduction is the fastest possible route to a fix.

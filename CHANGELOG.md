# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Nothing yet.

## [0.1.0] - 2026-07-25

First release.

### Added

- `RateLimiter`, a single interface over every algorithm, with `tryAcquire`, `peek`, `acquire`, `isAllowed`, `require` and `reset`.
- Six algorithms behind `RateLimiters`: `tokenBucket()`, `leakyBucket()`, `fixedWindow()`, `slidingWindowLog()`, `slidingWindowCounter()` and `gcra()`.
- `composite()`, enforcing several limits at once. Every delegate is checked before any is charged, so a request refused by a later limit does not consume a permit from an earlier one.
- `rules()`, routing keys to different limits by pattern, with `KeyMatcher.prefix`, `.exact`, `.regex` and `.any`.
- `RateLimitResult`, carrying `allowed`, `limit`, `remaining` and `retryAfter`, with `asHeaders()` rendering `X-RateLimit-*` and `Retry-After`. `Retry-After` rounds up, so a client that waits exactly that long succeeds.
- `LimiterStore`, a one-method SPI for pluggable storage, and `InMemoryStore`, bounded by default with LRU, LFU or no eviction and idle expiry.
- `TimeSource` and `MutableTimeSource`, shipped in the main artifact so callers can test their own limits without sleeping.
- `RateLimitListener` and `CompositeListener` for metrics, with no metrics dependency. `CompositeListener` swallows listener exceptions so a broken sink cannot break the request path.
- `RateLimitExceededException`, carrying the key and the full result.

### Notes

- No runtime dependencies, and no background threads: state refills lazily from elapsed time.
- Requires Java 17 or later.

[Unreleased]: https://github.com/mansi/rate-limiter/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/mansi/rate-limiter/releases/tag/v0.1.0

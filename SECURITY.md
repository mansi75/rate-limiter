# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| 0.1.x | ✅ |

While the project is pre-1.0, fixes land on the latest minor version only.

## Reporting a vulnerability

Please do not open a public issue.

Report privately through [GitHub Security Advisories](https://github.com/mansi75/rate-limiter/security/advisories/new). You should get an acknowledgement within a few days, and an assessment within two weeks.

Useful things to include: the affected version, the limiter configuration, and a reproduction — a `MutableTimeSource`-based test is ideal.

## What counts as a vulnerability here

This is a rate limiting library, so the interesting failures are ones that let a caller exceed a configured limit, or that let a caller exhaust the host's resources.

In scope:

- Traffic that exceeds a configured limit — a permit granted twice under concurrency, an arithmetic overflow that resets a counter, a clock reading that lets a window be replayed.
- Unbounded memory growth from attacker-controlled keys.
- A retry delay short enough that a client honouring it is admitted early.

Out of scope:

- **The fixed window over-granting at boundaries.** `fixedWindow()` permits twice the configured limit across a window boundary. This is inherent to the algorithm, documented in the README and asserted in `FixedWindowBoundaryTest`. Use `slidingWindowCounter()` if that matters.
- **The sliding window counter's approximation.** It estimates the previous window's contribution assuming even distribution, with bounded error. That is the documented trade-off.
- **An unbounded `InMemoryStore`.** `maxKeys(0)` and `EvictionPolicy.NONE` remove the bound on request. The default is bounded precisely because keys usually come from untrusted input.
- Denial of service against a `RateLimitListener` you supplied. Callbacks run inline on the calling thread; a slow listener slows down the caller, as documented.

## Note on distributed stores

`InMemoryStore` limits one JVM. A cluster of *n* nodes each running it admits up to *n* times the configured limit in aggregate — that is a deployment property, not a vulnerability. If you need a cluster-wide limit, implement `LimiterStore` against a shared backend that can execute the mutation server-side.

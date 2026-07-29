## What this changes

<!-- One or two sentences, from the point of view of someone calling the library. -->

## Why

<!-- The problem it solves. Link the issue if there is one. -->

Closes #

## Behaviour changes

<!--
Anything an existing caller would notice: a different decision, a different retryAfter,
a renamed option, a new default. Say "none" if there are none — a surprise in a
dependency is worse than a missing feature.
-->

None.

## Checklist

- [ ] A test that fails without this change
- [ ] No `Thread.sleep` in tests — used `MutableTimeSource` instead
- [ ] No new `implementation` or `api` dependency (test-only is fine)
- [ ] Javadoc on anything public
- [ ] CHANGELOG entry under `Unreleased`, if callers can see the change
- [ ] `./gradlew build` passes

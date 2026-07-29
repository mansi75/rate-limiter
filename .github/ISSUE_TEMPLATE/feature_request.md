---
name: Feature request
about: Something you are trying to limit that the current options do not express
title: ''
labels: enhancement
assignees: ''
---

## What are you trying to limit

<!-- The real situation, not the API you have in mind for it. Concrete beats abstract. -->

## Why the current options do not express it

<!--
Which you tried, and where each fell short. The six algorithms, `composite()` for several
limits at once, and `rules()` for different limits per key cover most shapes between them,
so it is worth saying which combination you got closest with.
-->

## What you would like

<!-- A sketch of the API, if you have one in mind. -->

```java
// optional
```

## Anything else

<!--
Two things that help a lot:

- Whether it needs to hold across a cluster. That usually points at a LimiterStore
  implementation rather than a change to the core.
- Whether it would require a runtime dependency. The library has none and intends to
  keep it that way, so features that need one generally arrive as an SPI instead.
-->

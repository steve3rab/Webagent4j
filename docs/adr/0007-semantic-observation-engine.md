# ADR 0007: Semantic observation engine

## Context

Applications need a compact description of a page before selecting actions. Returning full HTML or
backend accessibility objects would expose implementation types, create unbounded payloads, preserve
secrets, and force every consumer to reconstruct the same semantics. `IPage` also needs to expose
observations without introducing a Maven cycle between the browser contract and the engine.

## Decision

WebAgent4J uses an accessibility-first semantic snapshot rather than a DOM dump. A browser adapter
captures one bounded, passive `PageSnapshot`; the backend-independent observation engine filters,
redacts, deduplicates, relates, and transforms it into an immutable `Observation`.

The model and capture SPI live in `webagent4j-observation-api`. The engine lives in
`webagent4j-observation`, and Playwright capture remains in the Playwright adapter. Elements reuse
Phase 2 roles and immutable locator definitions through portable `ElementReference` values.

Every collection and string-producing operation is bounded. Truncation is explicit. Ordinary input
values are excluded by default, and password, token, and payment values are irreversibly redacted
before they can enter a snapshot. Compact text, JSON, fingerprinting, and semantic diff are
deterministic over retained semantic data.

## Alternatives

- Full HTML was rejected because it is noisy, backend-shaped, unbounded, and unsafe.
- A direct Playwright accessibility-tree model was rejected because it leaks the first backend into
  stable contracts.
- Repeated per-element browser calls were rejected because they are slow and produce incoherent
  snapshots on dynamic pages.
- Including all input values by default was rejected because observation is commonly logged or
  serialized.
- Adding a general serialization framework was rejected for the MVP; the small renderer avoids
  imposing that dependency on every module.

## Consequences

Consumers receive small immutable data that can be rendered, compared, and used with locator/action
APIs without a browser handle. Backends must implement secure bounded capture and cannot place a raw
secret in the SPI. Observation is not an atomic DOM transaction, so detected mutation is reported.
The MVP deliberately limits iframe, shadow-DOM, full accessibility-tree, and extraction support while
leaving backend and model boundaries ready for later extensions.

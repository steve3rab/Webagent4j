# ADR 0003: Playwright as the first browser backend

**Status:** Accepted
**Supersedes:** None

## Context

WebAgent4J needs a production browser implementation while keeping browser-native types out of application contracts.

## Decision

Implement the first backend with Playwright behind `webagent4j-browser-api` and domain ports. Discover the provider through `ServiceLoader`. Public APIs do not expose native Playwright `Page`, `Locator`, `Frame`, or browser objects.

## Consequences

Playwright can evolve independently behind the adapter. Backend-specific race/timeout/identity handling remains implementation work and must preserve backend-neutral failure contracts. Additional backends may be added without changing application APIs when they can honor the same contracts.

# ADR 0002: Maven multi-module architecture

**Status:** Accepted
**Supersedes:** None

## Context

Browser automation, semantic location, observation, actions, crawling, workflow, recording, and extension contracts have different dependency and compatibility boundaries. A single artifact would make backend-neutral contracts depend on implementation concerns and would make optional runtime pieces unavoidable.

## Decision

Use a Maven multi-module reactor with narrow domain/API modules, implementation modules depending inward on those contracts, a BOM for supported managed artifacts, and separate test/example modules. Reserved empty modules are not application APIs merely because they are reactor modules.

## Consequences

Module edges become architecture and compatibility constraints. Cycles are prohibited. Changes to supported module boundaries require documentation and architecture review. The robustness module may remain profile-gated rather than part of the default reactor.

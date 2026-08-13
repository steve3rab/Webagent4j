# ADR 0006: Use a deterministic semantic locator engine

- Status: Accepted
- Date: 2026-08-13

## Context

WebAgent4J needs stable, explainable element resolution across dynamic applications and multiple
browser backends. Raw CSS and XPath couple callers to incidental markup, do not express user-visible
intent, and provide little evidence when resolution fails. A single DOM element may also be discovered
by several semantic strategies, while SPAs can detach and replace matching nodes between workflow
steps.

The locator design must therefore support native accessibility semantics, hierarchical scopes,
dynamic re-resolution, state and interactability constraints, deterministic ambiguity handling,
bounded work, and machine-readable diagnostics without coupling the public API to Playwright or an AI
provider.

## Decision

WebAgent4J uses a backend-neutral, exact-first semantic locator engine.

The immutable definitions and fluent contracts live in `webagent4j-locator-api`. The DOM module
depends on that small API so both pages and elements expose the same scoped finder contract without a
dependency cycle. The `webagent4j-locator` module owns planning, filtering, scoring, ambiguity,
stability, budgets, diagnostics, and events. Browser adapters implement `ILocatorBackend`; only the
Playwright adapter imports Playwright types.

The engine makes these decisions:

1. Execute deterministic semantic and explicit-selector strategies before any fuzzy fallback.
2. Ask each backend to declare capabilities instead of branching on adapter implementation types.
3. Give every discovered element a backend identity, deduplicate by that identity, and aggregate all
   independent evidence before scoring.
4. Treat requested role, name, label, attribute, id, test id, and state as hard constraints. Use
   visibility, enabled state, and interactability only as preferences when they were not requested.
5. Centralize cumulative scoring weights and clamp scores to a documented range. Keep score and match
   confidence as distinct values.
6. Rank with a stable comparator based on exact semantic evidence, state, score, confidence, global DOM
   order, and backend identity. Apply a configurable ambiguity margin before DOM-order tie breaking for
   `single()`.
7. Re-run the complete bounded plan for terminal resolution and polling. A reusable
   `IElementReference` resolves immediately before use; `stableFor` requires continuous identity and
   state stability and resets on replacement.
8. Model visibility and clickability separately. Interactability comes from a dedicated checker and
   reliable backend state, never from a `visible == clickable` shortcut.
9. Bound total duration, strategies, candidates, fuzzy work, and retained rejection diagnostics.
10. Retain immutable structured diagnostics and publish structured events through an injected listener,
    with no global mutable bus.
11. Keep custom strategy phases and priorities explicit while preserving the authoritative standard
    semantic order.
12. Keep the core deterministic and AI-independent. Future AI, vision, or self-healing capabilities may
    use the extension ports but are neither present nor required.

`STRICT`, `BALANCED`, and `PERMISSIVE` policies make fallback strength explicit. The default is
`BALANCED`; permissive resolution forces detailed diagnostics.

## Consequences

Role, accessible name, label, placeholder, title, alternative text, visible text, id, arbitrary
attribute, test id, CSS, and XPath searches share one engine and one diagnostic model. Implicit roles,
form-control semantics, landmarks, nested accessible text, and native label association can remain
backend-native.

The same element discovered through multiple routes becomes one stronger candidate instead of several
ambiguous duplicates. `first()`, `single()`, and `all()` have distinct deterministic contracts.
Dynamic DOM changes are handled without caching candidate lists or treating detached objects as stable
identity.

The extra API module and capability model add types, but they preserve dependency direction and make
unsupported behavior observable. Detailed state and coverage checks cost more than a raw selector;
exact-first planning, early stopping, and global work budgets contain that cost. Live pages and
elements remain backend-bound and non-thread-safe, while definitions, configuration, results, evidence,
and diagnostics are immutable.

## Alternatives

- Put scoped finder contracts directly in the engine module. Rejected because it creates a Maven
  dependency cycle with the DOM contract.
- Duplicate a second finder API for elements. Rejected because page and element resolution could
  diverge in planning, configuration, and diagnostics.
- Implement all matching with Java-side DOM scans. Rejected because it discards native accessibility
  computation and can materialize unbounded candidate sets.
- Return one candidate per discovery strategy. Rejected because duplicate discoveries create false
  ambiguity and lose cumulative evidence.
- Cache a successful element handle. Rejected because dynamic applications detach and replace nodes;
  semantic intent must be re-resolved.
- Treat state as scoring only. Rejected because a disabled, hidden, covered, or otherwise incompatible
  candidate must not win a query that requires the opposite.
- Make CSS, XPath, fuzzy matching, or AI the default strategy. Rejected because exact accessible
  semantics are more deterministic, portable, inspectable, and user-centered.

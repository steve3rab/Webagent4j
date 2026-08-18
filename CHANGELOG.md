# Changelog

All notable changes to this project will be documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Java 21 Maven multi-module foundation and dependency BOM.
- Backend-neutral browser, DOM, observation, locator, action, and verification APIs.
- Playwright Chromium adapter and first end-to-end semantic navigation vertical.
- Unit, architecture, and browser integration tests.
- CLI commands for version, observation, inspection, and screenshots.
- `IPreparedAction.plan()`, returning an immutable, side-effect-free `IActionPlan<R>` that shares
  target resolution and precondition evaluation with `execute()`/`dryRun()` and always revalidates
  against the live DOM before `IActionPlan.execute()` runs the backend.
- `ILocatorScope<E>`, a typed contract for `ILocator`/`IFind`'s `within(...)`/`inContext(...)`,
  implemented by `InteractionContext`.
- `io.webagent4j.integration.coverage.PlaywrightCoverageGate`, a real, automated, enforced aggregate
  line-coverage gate for `webagent4j-browser-playwright`, replacing the skipped per-module JaCoCo
  `check` with a threshold check against the module's real cross-module coverage.

### Fixed

- Fixed mixed explicit/structured scope ordering in Playwright locator chains: a chain mixing
  `within(element)` and `within(structuredScope)` now always resolves in exactly the order the
  calls were declared, instead of implicitly applying every explicit element scope before any
  structured scope.
- `ActionExecutor` no longer emits `BACKEND_ACTION_STARTED`/`BACKEND_ACTION_COMPLETED` for a
  dry-run, and a dry-run now emits exactly one terminal `ACTION_COMPLETED` event.
- Target-resolution failures are classified through the typed `ILocatorFailure` contract instead of
  exception class names, so a genuine backend/runtime failure is never reported as
  `TARGET_NOT_FOUND`.
- `ILocator.tryFind()` now recognizes a typed locator failure wrapped by an unrelated
  `RuntimeException`, within a bounded, cycle-safe cause chain.
- `ActionResult.executionMode()` is now validated non-null.
- `PlaywrightScopeResolver`'s context resolution no longer catches a bare `RuntimeException` when
  falling back from accessible-name to visible-text matching: the fallback now triggers only on a
  demonstrated typed "not found" outcome, so an ambiguous context or a genuine backend/runtime
  failure always propagates instead of being silently retried under a different strategy.
- Every `InteractionContext.containingText(...)` constraint is now honored, in order, progressively
  narrowing the scope; previously only the first constraint was ever applied.
- `IActionPlan.execute()` may now be called at most once per plan instance; a second call throws
  `IllegalStateException` instead of risking a second real backend invocation.
- `IActionPlan.actionId()` and its eventual `IActionPlan.execute().actionId()` are now always equal.
- A structured locator scope (`within(ILocatorScope<E>)`) is no longer resolved once, eagerly, when
  the fluent chain is built. `PlaywrightFind`/`PlaywrightLocator` now keep it as a pending,
  backend-neutral definition and re-resolve it fresh at every terminal operation - `first()`,
  `single()`, `all()`, and every invocation of a `reference()`'s deferred `resolve()` - so a context
  that becomes ambiguous, disappears, or is replaced by a semantically different region between
  reference creation and action execution blocks the action instead of silently reusing whatever
  node it resolved to earlier. An explicit element scope (`within(E)`) is unaffected and stays
  eager, since the caller already handed over a concrete node.
- The JaCoCo per-module coverage comment on `webagent4j-browser-playwright`'s `coverage-check`
  execution incorrectly claimed the "report" goal was also skipped; only "check" ever was. The
  comment now matches the configuration, and the module's exemption is backed by a real enforced
  aggregate gate instead of being a bare, unreplaced skip (see `PlaywrightCoverageGate` above).

### Changed

- `IPreparedAction.dryRun()` and `IPreparedAction.plan()` are now mutually exclusive: calling
  `plan()` after `dryRun()` on the same prepared action throws `IllegalStateException`.
- `ILocator`/`IFind`'s `within(Object)`/`inContext(Object)` were replaced with typed overloads,
  `within(E)` and `within(ILocatorScope<E>)`.
- `ActionPlan` is now the `IActionPlan` interface; its sole implementation, `DefaultActionPlan`, is
  package-private. A plan is obtainable only through `IPreparedAction.plan()` - there was never a
  public usage of the old public constructor outside the module's own pipeline and tests, so there
  is no public migration path to document beyond the type rename.

### Deprecated

- `ActionResult(boolean, T, Duration, List, Optional)`, which cannot represent a dry-run or
  not-executed outcome; use the canonical constructor or the new explicit-`ActionExecutionMode`
  overload.

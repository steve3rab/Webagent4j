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
- `IPreparedAction.plan()`, returning an immutable, side-effect-free `ActionPlan<R>` that shares
  target resolution and precondition evaluation with `execute()`/`dryRun()` and always revalidates
  against the live DOM before `ActionPlan.execute()` runs the backend.
- `ILocatorScope<E>`, a typed contract for `ILocator`/`IFind`'s `within(...)`/`inContext(...)`,
  implemented by `InteractionContext`.

### Fixed

- `ActionExecutor` no longer emits `BACKEND_ACTION_STARTED`/`BACKEND_ACTION_COMPLETED` for a
  dry-run, and a dry-run now emits exactly one terminal `ACTION_COMPLETED` event.
- Target-resolution failures are classified through the typed `ILocatorFailure` contract instead of
  exception class names, so a genuine backend/runtime failure is never reported as
  `TARGET_NOT_FOUND`.
- `ILocator.tryFind()` now recognizes a typed locator failure wrapped by an unrelated
  `RuntimeException`, within a bounded, cycle-safe cause chain.
- `ActionResult.executionMode()` is now validated non-null.

### Changed

- `ILocator`/`IFind`'s `within(Object)`/`inContext(Object)` were replaced with typed overloads,
  `within(E)` and `within(ILocatorScope<E>)`.

### Deprecated

- `ActionResult(boolean, T, Duration, List, Optional)`, which cannot represent a dry-run or
  not-executed outcome; use the canonical constructor or the new explicit-`ActionExecutionMode`
  overload.

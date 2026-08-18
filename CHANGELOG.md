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
- `webagent4j-wait`, a new backend-neutral module carrying the one deterministic wait/stability
  primitive (`WaitEngine`, `WaitBudget`, `WaitPolicy`, `IWaitProbe`, `IMonotonicClock`,
  `IWaitSleeper`) shared by locator resolution, verification polling, and action
  stabilization/postconditions - see `docs/wait-and-stability.md`. It depends only on the JDK and
  `webagent4j-common`, and no domain module depends on it in the other direction.
- `VerificationEngine.awaitAll(IVerificationContext, List, WaitBudget, Duration)`, an overload that
  shares one deadline across every condition in the list instead of giving each one an
  independent, full timeout.
- `WaitSample.pending(T)`, a pending sample carrying an informational last-known value - still
  retried exactly like `WaitSample.pending()`, but preserved in `WaitResult.value()` if the wait
  times out instead of being discarded.

### Fixed

- Completed `LocatorEngine`'s migration onto the shared, deterministic `WaitEngine`: its own
  `do`/`while` deadline, stability-timer, and sleep loop is gone, replaced by
  `WaitEngine.await(WaitBudget, WaitPolicy, IWaitProbe)` driving a single, non-looping DOM search
  per attempt. `LocatorResolutionWaiter`, which had no remaining callers once the loop moved, was
  deleted rather than kept as an unused compatibility wrapper.
- Fixed `locateSingle()` only checking ambiguity on the final candidate list returned by a wait,
  instead of on every individual poll: a second matching candidate that appeared and then
  disappeared again during a `stableFor(...)`/`waitUntilVisible()` wait could previously go
  unnoticed. Ambiguity observed on any poll now fails immediately with
  `AmbiguousLocatorException`, exactly like a genuine backend/runtime failure does, rather than
  being treated as a transiently-pending state the wait might resolve out of on its own.
- Fixed `ActionTargetResolver` retrying target resolution on any `RuntimeException`, including
  ambiguity and genuine backend/runtime failures. Only a demonstrated, typed `NOT_FOUND` outcome
  (a resolved-but-detached element counts as `NOT_FOUND` too) is retried now; ambiguity and any
  other failure end resolution on the first attempt.
- Fixed `ActionExecutor` computing target-resolution retries and postcondition verification
  against independently-converted `Duration` values derived from its budget, instead of the exact
  same shared `WaitBudget` instance: `ActionTargetResolver` and the new
  `VerificationPoller`/`VerificationEngine` `WaitBudget` overloads now consume that one object
  directly, with no remaining-to-fresh-budget conversion in between.
- Fixed the action pipeline never checking, immediately before invoking the backend, whether its
  global budget had already been exhausted by resolution and preconditions: a backend side effect
  is now never started once the action's budget has expired, and is never retried as part of
  wait/poll logic - a backend call already in flight when the deadline passes may still take
  longer to return, which is a deliberately narrower and true claim than "every action finishes
  before its timeout".
- Fixed action postconditions each silently receiving their own independent, full timeout instead
  of sharing the action's configured budget: `ActionExecutor` now starts one monotonic
  `WaitBudget` per execution and threads its shrinking `remaining()` through both stabilization and
  postcondition verification, so a list of postconditions can no longer add up to several times the
  configured timeout in the worst case.
- Fixed `VerificationPoller` measuring its polling deadline against wall-clock time
  (`Clock`/`Instant`) instead of a monotonic clock, and fixed it, `LocatorResolutionWaiter`, and
  `ActionTargetResolver`'s pre-execution retry loop each owning their own direct
  `Thread.sleep`/`LockSupport.parkNanos` call: all three now delegate to the shared
  `webagent4j-wait` primitive.
- Fixed `WaitBudget.start(...)` letting `Duration.toNanos()` throw `ArithmeticException` for an
  implausibly large timeout (for example `Duration.ofSeconds(Long.MAX_VALUE)`) instead of
  saturating like every other overflow path in the same class.

- Fixed explicit-element scopes being able to escape a previously declared parent scope in mixed
  locator chains: an explicit element declared after another scope is now proven, against the real
  Playwright DOM relationship, to be a descendant of (or the same node as) that current scope before
  it is accepted - resolution fails explicitly instead of silently narrowing to an unrelated element,
  even one that contains a perfectly valid target of its own. This check is re-run at every terminal
  operation, so a child moved out of its declared parent between building a reference and resolving
  it is rejected too, not just at chain-build time.
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

- `VerificationPoller`'s `Clock`-based constructor was replaced by
  `VerificationPoller(io.webagent4j.wait.WaitEngine)`: the poller now measures its deadline
  against a monotonic clock, never wall-clock time, so a wall-clock-based constructor could only
  perpetuate the exact bug this change fixes. The unused `Clock` constructor had no callers outside
  the module's own default, so there is no other public migration to document.
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

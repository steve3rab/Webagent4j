# Changelog

All notable changes to this project will be documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (Frame / iframe support)

- `IPage#frame()` / `IFrame#frame()`, returning a new `IFrameLocator`: a backend-neutral, immutable
  frame query with `withId`/`named`/`withTitle`/`withUrl`/`timeout`/`stableFor` criteria and
  `single()`/`tryFind()` terminal operations - the same 0/1/N -> not-found/success/ambiguous
  classification, bounded-wait semantics, and no-DOM-order-tie-breaker guarantees element locators
  already have. A frame is modeled as a document boundary, never a descendant DOM element; no native
  Playwright `Frame`, `FrameLocator`, or `Page` type is exposed through the public API.
- `IFrame`: a re-resolvable live frame handle exposing `find()`, `action()`, `observe()`/
  `observe(ObservationOptions)` (scoped only to that frame's own document), `url()`, `title()`,
  `navigate(String)`, and `frame()` for traversal nested strictly inside that frame's own document.
  `IFrame implements IActionContext, IObservationSource`, so `dryRun()`, `plan()`/`IActionPlan`,
  postcondition verification, and `tryFind()` all work identically inside a frame as they already do
  at the page level, including full revalidation of the frame boundary itself (not just the target
  inside it) before `IActionPlan.execute()` touches the backend.
- `FrameDefinition`, an immutable record mirroring `LocatorDefinition`'s copy-on-write pattern for
  frame criteria.
- Nested frame traversal, cross-origin iframe support (without weakening browser security), and
  transparent following of a removed-and-replaced `<iframe>` matching the same semantic identity.
- Extended `IPendingScope` (`webagent4j-browser-playwright`) with a `Frame` case, reusing the
  existing pending-scope/live-resolution architecture rather than a parallel frame engine; a
  prerequisite frame hop inside a longer chain stays bounded to a one-shot probe, never a nested
  full-timeout wait.
- Widened `IObservationEngine`/`ObservationEngine` from `IPage` to the pre-existing
  `IObservationSource` supertype, letting `IFrame.observe()` reuse the same observation engine
  without duplicating it; existing `IPage`-based callers are unaffected.
- 25 new Playwright integration tests (`FrameResolutionIT`, `FrameAmbiguityIT`, `FrameNestedIT`,
  `FrameLifecycleIT`, `FrameActionPlanIT`, `FrameDryRunAndTryFindIT`, `FrameNavigationIT`,
  `FrameCrossOriginIT`) and 10 new deterministic robustness scenarios (`FrameRobustnessIT`,
  FRAME-001..FRAME-010).

### Fixed (Frame / iframe consistency)

- **URL now genuinely participates in frame resolution instead of being checked only after
  id/name/title already settled on a single candidate.** Previously `PlaywrightScopeResolver`
  resolved the `id`/`name`/`title` criteria through `ILocatorEngine#locateSingle` - which fails
  closed on more than one match by itself - before the `url` criterion was ever consulted, so two
  `<iframe>`s sharing the same `name` but different `src` were incorrectly rejected as `AMBIGUOUS`
  even when `.withUrl(...)` should have disambiguated them. Frame resolution now discovers every
  current `id`/`name`/`title` candidate through `ILocatorEngine#locateAll`, filters that set by the
  `url` criterion when present, and only then applies the 0/1/N -> not-found/success/ambiguous
  classification - the same fix applies to a `url`-only query against several candidates. The fix
  reuses the existing `webagent4j-wait` `WaitEngine`/`WaitBudget`/`WaitPolicy` primitives (one more
  caller of the shared wait architecture, not a second resolution engine), preserves the existing
  one-shot-versus-real-timeout split for a prerequisite frame hop, keeps bounded waits, `stableFor`,
  live re-resolution, nested frames, and typed classification exactly as before.
- **A genuine backend or runtime failure encountered while inspecting a URL candidate is no longer
  absorbed as "this candidate does not match".** The URL-filtering step previously caught every
  `RuntimeException` around a candidate's URL check and treated all of them alike as "vanished",
  which could silently turn a disconnected browser or a closed context into a typed
  `LocatorNotFoundException` - or an empty `tryFind()` result - instead of surfacing the real
  failure. It now distinguishes three outcomes: the `<iframe>` element itself vanishing between
  discovery and inspection is Playwright's typed `TimeoutError` (bounded to a short explicit
  timeout, mirroring `PlaywrightLocatorBackend`'s existing candidate-vanishing idiom) and is
  correctly treated as "not currently matching" so the wait keeps polling; a content document that
  is present but not yet available or `Frame#isDetached()` is likewise a normal "not currently
  matching" state, with no exception involved; anything else now propagates unchanged, exactly like
  every other genuine backend failure elsewhere in this codebase.
- **A `FUZZY` URL criterion is now rejected explicitly instead of silently degrading to
  `CONTAINS`.** `FrameDefinition#withUrl(TextMatch)` (and its canonical constructor) now raises a
  `LocatorException` ("Frame URL matching does not support FUZZY") as soon as a `FUZZY` criterion is
  supplied, before any browser access is attempted. Frame URL matching supports exact,
  case-insensitive exact, contains, starts-with, ends-with, and regex only - never fuzzy.
- **`IFrameLocator#first()` and `#all()` removed.** `first()` was a redundant alias for `single()`,
  and `all()` returned the same `IFrame` handle repeated N times with no individual stable identity -
  a misleading contract for a document boundary, which has no scoring dimension to rank candidates
  by. `IFrameLocator` now exposes only `single()` and `tryFind()` as terminal operations; element-
  level `ILocator#first()`/`#all()` are unaffected.
- **`IFrame#locate(LocatorDefinition)` and `#locate(LocatorDefinition, LocatorConfig)` are now fully
  live**, resolving this frame's own pending-scope chain fresh on every `WaitEngine` poll instead of
  once before the wait begins - the same re-resolution guarantee `frame.find()...single()` and
  `IFrame#find(LocatorConfig)` already had. A frame that is replaced, disappears, or becomes
  ambiguous mid-wait is now caught by `locate(...)` exactly as it already was by `find(...)`, with
  identical semantics between the fluent and programmatic entry points.
- `FrameDefinition`'s Javadoc for `id` now names `<iframe>` explicitly instead of "iframe/frame"
  (this codebase never added legacy HTML `<frame>` support). `requirePositive()` now takes a label
  so a non-positive `timeout` and a non-positive `stableFor` duration raise distinct, correctly
  worded messages instead of both saying "timeout must be positive".
- 11 new tests covering the url-participates-before-classification fix and the live `locate()` fix:
  `FrameUrlResolutionIT` (6 real-browser scenarios: same-name-different-url disambiguation,
  same-name-same-url ambiguity, url-only selection among several, nonexistent url, frame replacement
  retaining url-based identity, nested frame with a url criterion) and `FrameLocateLiveResolutionIT`
  (5 real-browser scenarios: `locate()` after replacement, `locate()` NOT_FOUND on disappearance
  mid-wait, `locate()` AMBIGUOUS on a duplicate appearing during `stableFor`, nested-frame `locate()`,
  no wrong target leaking from a sibling frame), plus 14 new unit tests (27 total) in
  `PlaywrightFrameScopeResolverTest` covering every `TextMatch` type against the `url` criterion and
  the disambiguation/ambiguity/not-found matrix at the mocked-engine level.
- 5 further tests covering the backend-failure-propagation and `FUZZY`-rejection fixes: a
  `TimeoutError`-vanished candidate and a genuine backend failure during URL inspection (each
  proving the opposite outcome of the other) in `PlaywrightFrameScopeResolverTest` (now 27 total); a
  new `PlaywrightFrameLocatorTest` proving `tryFind()` never converts a URL-inspection backend
  failure into an empty `Optional`; and two new `FrameDefinitionTest` cases (now 10 total) proving
  `withUrl(TextMatch)` and the canonical constructor both reject `FUZZY` explicitly.

### Fixed (`LocatorEngine` timed-out wait no longer masquerades as success)

- **A `stableFor` wait that times out can no longer return the last candidate it happened to
  observe as though the wait had actually succeeded.** `LocatorEngine#resolve()` unconditionally
  read `WaitResult#value()` regardless of `WaitResult#status()`. On `WaitStatus.TIMED_OUT`, that
  value is the *last polled* `WaitSample`, which - per `WaitSample#pending(Object)`'s own contract -
  is preserved only for diagnostics and may legitimately carry a real, non-empty candidate list when
  the target was found but interrupted before its requested stability window elapsed (for example, a
  frame that disappears partway through a `stableFor` wait: the poll immediately before the
  disappearance is a genuine `WaitSample.satisfied(...)`, even though the wait as a whole never
  stabilizes). `LocatorEngine` was treating that diagnostic-only value as a real result, silently
  turning a genuine timeout into a false success. `resolve()` now only populates the final candidate
  list when `WaitResult#status() == WaitStatus.SUCCESS`; a `TIMED_OUT` result always resolves to no
  candidates, regardless of what the last poll observed. This was surfaced by, and fixes,
  `FrameLocateLiveResolutionIT.locateFailsAsNotFoundWhenTheFrameDisappearsDuringTheWait`, which had
  never previously exercised a real "found, then interrupted mid-`stableFor`" sequence against an
  actual browser. Live re-resolution (`IFrame.locate()` re-walking its full pending-scope chain on
  every poll), `stableFor`/timeout semantics, and every other locator/frame contract are unchanged.
- 5 new deterministic fake-time unit tests in `LocatorEngineWaitIntegrationTest` (now 9 total),
  reusing its existing `FakeClock`/`AdvancingSleeper`/`StagedBackend` harness: a candidate present on
  every poll but never stable long enough before timeout; a candidate that disappears once (resetting
  the stability window) and reappears but still not for long enough before timeout; a candidate that
  genuinely remains stable for the full window, succeeding with that candidate; a no-`stableFor`
  candidate present on the first poll still succeeding immediately with zero sleeps (no regression);
  and a dedicated headline regression test (`doesNotReturnLastObservedCandidateWhenStabilityTimesOut`)
  proving a `TIMED_OUT` result with a non-empty last-observed sample can never become a `LocatorEngine`
  success, additionally asserting the `LocatorResolutionStatus.TIMEOUT` / `BudgetLimit.TIMEOUT`
  diagnostics stay correct.
- **A `CASE_INSENSITIVE_EXACT` accessible-name/label/title/alt-text/visible-text criterion (the match
  type behind `named(String)`, this codebase's most common locator entry point) now actually matches
  case-insensitively at the Playwright discovery layer, instead of silently discovering nothing
  whenever the DOM text's case differs from the requested value.** `PlaywrightLocatorBackend#exact`
  mapped both `EXACT` and `CASE_INSENSITIVE_EXACT` to Playwright's native `exact: true` option -
  but Playwright's own `exact: true` is case-*sensitive* and does not trim/collapse whitespace, so a
  `CASE_INSENSITIVE_EXACT` criterion whose case differed from the DOM's actual text (for example
  `.named("CRÉER le compte")` against a button whose real accessible name is "Créer le compte")
  discovered zero native candidates through every deterministic strategy (`ACCESSIBLE_NAME`, `LABEL`,
  `VISIBLE_TEXT`), forcing a fallback all the way to `FUZZY_TEXT` - whose candidates `LocatorScorer`
  can never mark as an exact match (`exact = !fuzzy`). This was previously invisible because the
  `LocatorEngine` timeout bug fixed above silently returned that non-exact fallback candidate as
  though the wait had succeeded; with that bug fixed, the wait now (correctly) never finds an exact
  match and times out. `exact(TextMatch)` now maps only `EXACT` to Playwright's `exact: true`;
  `CASE_INSENSITIVE_EXACT` uses Playwright's own loose, case-insensitive substring discovery and
  relies - exactly like `FUZZY_TEXT` already does - on `LocatorScorer`'s own strict, case-folded
  full-string comparison (via `TextMatcher`) to accept only a genuinely case-insensitive-exact
  candidate and reject every other loosely-discovered one. Surfaced by, and fixes,
  `SemanticLocatorIT.supportsUnicodeNestedAccessibleNamesAndConfiguredTestIds` against a real
  browser; confirmed via CI history that this test passed before the `LocatorEngine` fix above and
  failed immediately after it, with no other change in between.

### Fixed (CI stabilization)

- Fixed three intermittent/deterministic integration-test failures exposed by CI (all traced to test
  fixture/orchestration bugs, not production defects):
  - `ActionPlanIT.revalidationBlocksExecutionWhenThePreconditionStopsHolding` - its fixture disabled
    the "Confirm" button via a bare `confirm.disabled = true` reference, which collides with the
    browser's built-in `window.confirm` dialog function and could silently do nothing. Fixed by
    referencing the element through `document.getElementById('confirm')` inside an explicit,
    test-invoked `disableConfirmButton()` function instead of an implicit id-derived global.
  - `ActionPlanScopeContainmentRevalidationIT.aPlanWhoseExplicitChildIsMovedOutsideItsParentFailsInsteadOfExecuting` -
    its fixture moved `#panel` via a `setTimeout(..., 150)` that raced against the test's own
    (variable-latency) locator resolution and `plan()` construction. Fixed by exposing an explicit
    `movePanelToProductB()` fixture function, invoked from the test immediately after asserting the
    plan is `READY`, removing the race entirely.
  - `ActionTimeoutIT.boundsAnUnmetPostconditionAndKeepsThePageUsable` - asserted a server-side click
    count against the shared default `/actions/click` fixture, whose "Increment" button never called
    the counting endpoint; the assertion was unconditionally wrong, not flaky. Fixed with a dedicated
    `/actions/click-timeout-oracle` fixture and two independent oracles (a synchronous DOM counter and
    a briefly, boundedly polled server-side counter), both asserting exactly one backend invocation.
  - The same `setTimeout`-plus-eager-pre-resolution race pattern was also present in, and fixed the
    same way in, `ExplicitScopeMovedOutsideParentIT`, `ExplicitScopeDetachmentProtectionIT`, and
    `ActionPlanMixedScopeRevalidationIT` (new `movePanelToProductB()`/`detachOuterContainer()`/
    `replaceProductAAvailableRegion()`/`addDuplicateConfirmButton()`/
    `replaceConfirmButtonWithFreshNode()`/`replaceConfirmButtonWithUnrelatedDeleteButton()` fixture
    functions in `ActionTestApplication`, invoked explicitly instead of raced against a timer).
- Fixed `PlaywrightCoverageGate`'s aggregate-coverage `exec-maven-plugin` execution
  (`coverage-check-playwright-aggregate`) failing with "JaCoCo aggregate CSV not found" on every
  real run - this had never been reached before this mission's other CI fixes, since the Failsafe
  stage always failed first. Root cause: `exec-maven-plugin`'s `java` goal runs its main class
  in-process, in the same JVM as the whole reactor build, with no working-directory parameter to
  set - `PlaywrightCoverageGate`'s relative default path resolved against the JVM's own `user.dir`
  (the repository root `mvn` was launched from), not this module's own `target/` directory. Fixed
  by passing the CSV path explicitly as an absolute `${project.build.directory}/...` argument;
  `PlaywrightCoverageGate.main(String[])` already supported an explicit path argument, so no Java
  code changed.
- Fixed GitHub Actions CI ("CI / Java 21 / Linux") not installing the Linux OS packages Chromium
  needs to launch (only the browser binary itself was installed), causing "missing dependencies to
  run browsers" failures. Added an opt-in `ci-playwright-deps` Maven profile to
  `webagent4j-integration-tests` and `webagent4j-robustness-tests`, running
  `com.microsoft.playwright.CLI install-deps chromium` - Playwright's own supported host-dependency
  installer - activated only by `.github/workflows/ci.yml` and the Linux legs of
  `.github/workflows/nightly.yml`; a normal local `clean verify`, on any OS, never activates it and
  never requires `sudo`.
- Enabled the previously `if: false`-disabled `.github/workflows/dependency-review.yml` check, so it
  performs a real dependency review on pull requests instead of reporting a misleading, no-op green
  check.
- Added `PlaywrightCoverageGateTest.resolvesAnExplicitAbsolutePathIndependentlyOfTheProcessWorkingDirectory`,
  proving the property the `coverage-check-playwright-aggregate` fix above actually depends on: an
  explicit path argument is resolved as-is, never relative to the process's current working
  directory.
- Removed a redundant `ci-playwright-deps` activation from `.github/workflows/ci.yml`'s second
  `mvnw` invocation ("Verify core robustness subset"): both steps run on the same job/runner, and
  the first step's `webagent4j-integration-tests`-scoped `install-deps chromium` already installs
  the OS packages the second step needs, since apt state persists for the rest of the job - the
  second activation just re-ran an already-satisfied `apt-get install`.
- Documented, in `docs/testing.md`, why CI's "Playwright Host validation warning" (~35 missing
  libraries, e.g. `libgtk-4.so.1`, the `libgst*`/`libflite*` sets) is expected and benign for this
  Chromium-only suite rather than something to silence: it traces (via the Playwright driver's own
  bundled dependency tables) entirely to WebKit's dependency group, and originates from a
  driver-internal, zero-argument auto-install check the Java driver runs on first browser launch -
  separate from, and in addition to, this project's own Chromium-scoped `install`/`install-deps`
  steps, which are confirmed correctly scoped and not the source of the warning.
- Fixed a deterministic CI hang: `install-deps chromium`'s runtime `apt-get update` started
  stalling indefinitely on an Ubuntu/Azure mirror inside GitHub Actions' network, reproduced twice,
  cancelling the job at its 30-minute timeout both times. Moved `ci.yml`'s "Java 21 / Linux" job and
  `nightly.yml`'s Linux jobs onto `container: mcr.microsoft.com/playwright/java:v1.60.0-noble` -
  the same image the repository's `Dockerfile` already builds from, at the matching Playwright Java
  version - which ships Chromium and its Linux host dependencies pre-installed, removing the
  runtime `apt-get` path entirely rather than retrying around it. `-Pci-playwright-deps` is no
  longer activated by either workflow but remains available as an explicit opt-in Maven profile for
  environments that still need it; local builds are unaffected either way. Each container-based job
  still installs Temurin 21 via `setup-java` and verifies it with a `java -version`/`./mvnw
  --version` diagnostic step, since the base image ships a newer JDK. `nightly.yml`'s `docker build`
  verification steps moved into their own separate, non-containerized jobs, since the Playwright
  image doesn't ship a `docker` CLI.

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
- `ILiveLocatorContext`, plus matching overloads of `ILocatorEngine.locate()`/`locateSingle()`/
  `locateAll()`: `baseline()` supplies the stable backend/configuration a wait needs before it has
  resolved anything, and `resolve()` is called fresh on every polling attempt instead of once
  before the wait begins, so a structured semantic scope a live context depends on is re-evaluated
  against the current DOM throughout a wait, not only when it starts. The existing `LocatorContext`
  overloads are now default methods delegating to a fixed (never-changing) live context, so every
  existing caller that already has one resolved context to search keeps working unchanged.

### Fixed

- Fixed `LocatorEngine` starting its `WaitBudget` against a separate `IMonotonicClock.systemClock()`
  instead of `waitEngine.clock()` - the same clock the rest of the wait polls and sleeps with. A
  `LocatorEngine` built with an injected fake clock (every deterministic wait test) previously still
  measured its deadline against real wall-clock time, so a wait that should time out instantly under
  fake time would instead busy-loop until real time actually passed the configured timeout.
- Fixed a structured semantic scope (`InteractionContext.containingText(...)`) being resolved only
  once per terminal operation instead of on every individual polling attempt of that operation's own
  wait: `PlaywrightLocator` now supplies an `ILiveLocatorContext` whose `resolve()` re-runs the whole
  pending scope chain fresh on each poll, so a scope that becomes ambiguous, disappears, or is
  replaced mid-wait is observed on the very next poll instead of only at the moment the wait started
  or ended. Each structured-scope container lookup this triggers is itself bounded to one immediate,
  non-waiting probe, so re-resolving the scope chain inside one outer poll attempt never starts a
  second, nested full-timeout wait - the whole logical wait remains governed by the one outer
  `WaitBudget`.
- Fixed context ambiguity only being a fail-safe condition for the final target, not for a structured
  scope the target depends on: a `containingText(...)` constraint that matches two regions on any
  poll now fails immediately with `AmbiguousLocatorException`, unconditionally - including through
  `locate()`/`locateAll()`, not only `locateSingle()` - and even when the target itself would still
  be unique if the ambiguous context were ignored (a duplicate region with no matching target inside
  it does not make the context safe).
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

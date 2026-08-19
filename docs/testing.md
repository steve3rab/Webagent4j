# Testing

Unit tests end with `Test` and run under Surefire. Integration tests end with `IT` and run under
Failsafe. The Playwright integration test starts a local JDK HTTP server on a random loopback port; it
does not depend on a public website. It covers browser launch, navigation, observation, semantic
location, click, URL verification, and cleanup.

`webagent4j-wait`'s own tests (`WaitEngineTest`, `WaitBudgetTest`) run entirely on fake time: a
`FakeMonotonicClock` and `FakeWaitSleeper` advance the clock by exactly the requested sleep
duration instead of really sleeping, so a test asserting a 60-second timeout completes in
milliseconds while still exercising the engine's real interval, deadline, and stability-window
arithmetic. Domain-level tests that need deterministic timing (`VerificationSharedBudgetTest`,
`LocatorEngineWaitIntegrationTest`) use the same pattern by injecting a `WaitEngine` built from
fake `IMonotonicClock`/`IWaitSleeper` implementations - `LocatorEngine` has a package-private
constructor for exactly this purpose. `LocatorEngineWaitIntegrationTest`'s `StagedBackend` also
demonstrates a reusable pattern for these tests: results advance once per sleep (i.e. once per
`WaitEngine` attempt boundary) rather than once per raw backend call, since one attempt can query
several locator strategies internally.

`LocatorEngineWaitIntegrationTest.aNeverSatisfiedWaitTimesOutByFakeTimeInsteadOfBusyLoopingOnRealWallClockTime`
is a regression test for one specific, previously real bug: `LocatorEngine` starting its
`WaitBudget` against a real system clock instead of `waitEngine.clock()`, the same fake clock the
rest of the wait ran on. That mismatch would not make a fake-time test wrong so much as slow - the
budget's real deadline would never see the fake clock's advances, so the engine would busy-loop
until real wall-clock time itself happened to pass the configured timeout. The test turns this into
a fast, deterministic failure by asserting the wait's real wall-clock duration stays under a second
despite a nominal five-second timeout, rather than merely being suspicious of a slow test run.

Dynamic-context wait coverage lives in two layers, on purpose. `LocatorEngineWaitIntegrationTest`
exercises `LocatorEngine` itself on fake time, so it should not also exercise a second, independent
temporal coordinator's timing - structured semantic scope resolution belongs to
`webagent4j-browser-playwright`, and `PlaywrightScopeResolver`'s own unit tests
(`PlaywrightScopeResolverTest`) already cover its fallback/ambiguity/backend-failure classification
in isolation with a mocked `ILocatorEngine`. Proving the *combination* - a structured scope
genuinely re-resolved fresh on every poll of a real, wall-clock-timed wait, against a real browser -
needs a real DOM that changes mid-wait, which only an IT-level browser fixture can provide:
`DynamicContextAmbiguityDuringWaitIT`, `DynamicContextDisappearanceDuringWaitIT`, and
`DynamicContextReplacementDuringWaitIT` each start a `stableFor(...)` wait before a fixture page's
`setTimeout` mutates the DOM partway through it (see `ActionTestApplication`'s
`context-dynamic-ambiguous`/`-target-unique`/`-disappears`/`-replaced` routes), and assert on the
resulting exception (or lack of one) plus independent server-side click counters - real wall-clock
timing here is unavoidable and acceptable, bounded by short, generous, non-flaky durations (a
150ms DOM mutation inside a 300ms stability window and an 800ms-2s overall timeout), consistent
with every other Playwright-backed IT in this suite.

## Deterministic fixture mutations, not sleeps, for T0/T1 tests

A recurring pattern in this suite is: assert state at T0, mutate the fixture page, assert state at
T1. That transition must be driven explicitly, never by a fixed-delay guess. `ActionTestApplication`
fixtures that need a mid-test mutation expose it as a plain named JavaScript function declared in a
`<script>` block (for example `function movePanelToProductB() { ... }`), invoked from the test with
`page.evaluate("movePanelToProductB()")` immediately after asserting T0 - never inside a
`setTimeout(...)` the test then has to out-wait with `Thread.sleep(...)`, `page.action().waitFor(...)`,
or any other blind delay. A `setTimeout`-driven mutation races the test: how much real wall-clock
time elapses between page load and the test reaching its T0 assertion is unbounded (locator
resolution, plan construction, and CI-runner load all vary independently of the fixture's timer), so
a fixed delay can fire before the test is ready, or the test can move on before the delay fires.
Several previously-flaky IT fixtures (`plan-precondition-invalidates`, `mixed-scope-child-moved`,
`mixed-scope-detached-child`, `mixed-scope-product-dynamic`, and the `plan-*` ambiguity/wrong-target
fixtures) follow this pattern now. Also avoid referencing an element by its bare `id`-derived global
(`confirm.disabled = true`) instead of `document.getElementById('confirm')` - `confirm` in particular
collides with the browser's built-in `window.confirm` dialog function, so the bare reference can
silently resolve to the wrong thing instead of throwing.

This does not apply to tests whose whole point is temporal behavior - a delayed postcondition, a
target that appears later, a context that becomes ambiguous, disappears, or is replaced while an
active `stableFor(...)`/`waitUntilVisible()` wait is still polling (`DelayedVerificationIT`,
`DynamicContextAmbiguityDuringWaitIT`, `DynamicContextDisappearanceDuringWaitIT`,
`DynamicContextReplacementDuringWaitIT`, `SemanticLocatorIT`'s stability tests). Those legitimately
keep a `setTimeout`-driven mutation racing against the wait itself, because exercising that race
safely is the entire point of the test - see the wait/stability coverage discussion above.

An "exactly once" backend-invocation assertion needs its own oracle discipline: prefer a DOM-side
counter updated synchronously in the same event handler that fires the backend call, since Playwright
guarantees that handler has already run by the time the click action returns control - no polling
needed for it. A separate server-side counter (an async `fetch(...)` from the same handler) should be
polled with a short, bounded loop until it reaches the expected value, then asserted equal - never
assumed to have already landed immediately after the action returns, and never awaited with one blind
sleep sized to "probably enough". See `ActionTimeoutIT` for both oracles used together.

## CI Playwright prerequisites

`webagent4j-integration-tests` and `webagent4j-robustness-tests` each bind an `exec-maven-plugin`
execution at `pre-integration-test` that downloads the Chromium browser binary via
`com.microsoft.playwright.CLI install chromium` - this runs unconditionally, on every OS, in every
`clean verify`, including a normal local developer build. It does not install the Linux OS packages
Chromium needs to actually launch (`libnss3`, `libatk-bridge2.0-0`, and similar); attempting to
launch without them fails with "missing dependencies to run browsers" or an opaque driver crash.

Installing those packages needs `apt`/`sudo` and is Linux-specific, so it is not part of the default
build: each of those two modules additionally declares a `ci-playwright-deps` Maven profile (inactive
by default) whose own `exec-maven-plugin` execution runs
`com.microsoft.playwright.CLI install-deps chromium` - Playwright's own supported host-dependency
installer, not a hand-maintained apt package list. `.github/workflows/ci.yml` activates this profile
explicitly (`-Pci-playwright-deps`); a normal local `./mvnw clean verify` on any OS never activates
it and never needs `sudo`. `.github/workflows/nightly.yml`'s Linux legs do the same; its macOS and
Windows legs do not, since `install-deps` is Linux-only.

ArchUnit checks package cycles, interface naming, and the core/Playwright boundary. JaCoCo writes
module reports and an aggregate report under `webagent4j-integration-tests/target/site/jacoco-aggregate`.
Source-bearing modules with direct tests must keep at least 70% line coverage, enforced by the parent
POM's `jacoco:check` execution at `verify`.

`webagent4j-browser-playwright` is the one exception, and its exemption is itself enforced, not just
documented: most of the adapter is only meaningfully exercised through
`webagent4j-integration-tests`' browser-driven IT suite, not through the adapter's own narrow,
browser-free unit tests, so the standard per-module `jacoco:check` (which can only ever analyze the
current module's own compiled classes - the stock plugin has no cross-module aggregate mode) is
skipped there (see the comment on that execution in `webagent4j-browser-playwright/pom.xml`). The
replacement is `webagent4j-integration-tests`' `coverage-check-playwright-aggregate` execution: bound
at `verify`, after `report-aggregate` produces `jacoco.csv`, it runs
`io.webagent4j.integration.coverage.PlaywrightCoverageGate`, which sums the `LINE_MISSED`/`LINE_COVERED`
columns for every `io.webagent4j.browser.playwright(.*)` row in that CSV and fails the build if the
combined ratio is below the same 70% threshold. This is a real, automated, non-zero gate against the
adapter's actual combined coverage - not a report that nothing reads - and its parsing/threshold logic
is covered by `PlaywrightCoverageGateTest`, independent of a real JaCoCo run. In an environment where
the Playwright browser download is blocked (a sandbox with no access to `cdn.playwright.dev`, for
example), this gate legitimately fails, the same way the Failsafe IT suite does: that reflects real
missing coverage, not a defect in the gate.

Testcontainers is aligned in dependency management and available to integration tests, but V1 starts no
unused container.

Run `./mvnw clean verify`. Use `./mvnw spotless:apply` to format changes.

The local deterministic adversarial benchmark is isolated behind the `robustness` profile:

```bash
./mvnw -Probustness verify
```

Run one corpus scenario with `-Dscenario=HOSTILE-004`. The profile writes Markdown and JSON reports
under `webagent4j-robustness-tests/target` and captures failure-only screenshots, compact semantic
observations, HTML fixtures, and diagnostics. See the [robustness guide](robustness.md) for the corpus,
quality gates, baseline review policy, and contribution workflow.

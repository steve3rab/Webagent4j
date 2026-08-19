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

## Frame/iframe traversal coverage

Frame support has its own fixture, `FrameTestApplication`, and its own family of ITs
(`FrameResolutionIT`, `FrameAmbiguityIT`, `FrameNestedIT`, `FrameLifecycleIT`, `FrameActionPlanIT`,
`FrameDryRunAndTryFindIT`, `FrameNavigationIT`, `FrameCrossOriginIT`, `FrameUrlResolutionIT`,
`FrameLocateLiveResolutionIT`), separate from
`ActionTestApplication`'s element-only routes: a frame is a document boundary, and several scenarios
(ambiguity, replacement, detachment) need more than one live document open at once, which the
existing fixture's single-document model does not represent. `FrameCrossOriginIT` starts a *second*
independent `FrameTestApplication` on its own loopback port and embeds it as a cross-origin iframe -
still no public internet dependency, and Playwright's default cross-origin isolation stays fully in
effect.

Frame ambiguity introduced while a wait is actively polling (`FrameAmbiguityIT`) needed a new
`IFrameLocator#stableFor(Duration)` - the same stability guarantee `ILocator#stableFor(Duration)`
already gives element locators, applied to the frame boundary itself - since, unlike a structured
element scope, a uniquely-resolving frame criterion has nothing forcing its wait to keep polling
through a later-introduced duplicate otherwise. A frame that disappears while a target inside it is
being waited for does not need this: the frame's own pending-scope hop is re-resolved fresh on every
poll of the *element*-level wait already, so its removal surfaces as a typed not-found the moment the
next poll re-resolves the chain - no separate frame-level wait or stability requirement needed for
that case.

Ten additional deterministic, local-only scenarios (FRAME-001..FRAME-010) live in
`webagent4j-robustness-tests`' `FrameRobustnessIT`, adjacent to (not folded into) the fixed
hundred-scenario `RobustnessCorpus`: that corpus's `RobustnessScenario` model and its
`scenarios.size() != 100` invariant are element-only and were never designed for a document-boundary
concept, matching how `WebAgentCoreRobustnessIT`, `SemanticConsistencyIT`, and the other robustness
dimensions already live as their own sibling scenario sets rather than inside the corpus. It covers
duplicate-name ambiguity, wrong-frame protection with pixel-identical controls in two different
frames, a stable `id` criterion resolving correctly amid name/title decoys, a decoy whose title
mimics another frame's `name` criterion (and vice versa) without ever cross-satisfying it, nested
frames, absence (of the frame itself and of a target inside an existing one), delayed insertion, and
same-identity replacement.

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
installer, not a hand-maintained apt package list. A normal local `./mvnw clean verify`, on any OS,
never activates it and never needs `sudo`; it remains available as an explicit opt-in
(`-Pci-playwright-deps`) for any environment that genuinely needs it - a bare `ubuntu-latest` runner
with no Playwright image, for instance. **Neither `.github/workflows/ci.yml` nor
`.github/workflows/nightly.yml` activates it any more** (see below) - GitHub Actions' Linux jobs get
these packages a different way now.

### Linux CI runs inside the official Playwright Java container, not via runtime apt-get

`ci.yml`'s "Java 21 / Linux" job and `nightly.yml`'s "Verify on Linux" and "Full deterministic
robustness benchmark" jobs all run inside
`container: mcr.microsoft.com/playwright/java:v1.60.0-noble` - the same image the repository's own
`Dockerfile` already builds from, at the exact Playwright Java version (`1.60.0`) this project pins.
That image ships Chromium and its Linux host dependencies pre-installed, so these jobs never need to
run `install-deps`/`apt-get` at all. This replaced an earlier design where `-Pci-playwright-deps`
ran `install-deps chromium` at CI time on a bare `ubuntu-latest` runner: that worked when it worked,
but the underlying `apt-get update` sometimes hit a stalled Ubuntu/Azure mirror inside GitHub's
network and hung for the rest of the job's timeout window instead of failing fast - a real,
reproduced deterministic CI failure (confirmed twice, same hang point, same mirror), not a flaky
one-off. Moving Linux CI onto a container image that already has the dependencies removes that
runtime network dependency entirely rather than retrying around it or maintaining a package list by
hand.

Each of these container-based jobs still runs `actions/setup-java` for Temurin 21 and a `java
-version` / `./mvnw --version` diagnostic step immediately after: the base image itself ships a
newer JDK than 21, and this project must still be verified against Java 21, not whatever JDK the
image defaults to. `setup-java` installs Temurin 21 into the container and points `JAVA_HOME` at it
for every later step, so the actual `clean verify` run uses Java 21 - `./mvnw --version`'s own
"Java version: 21..." line is the proof, checked into the job log on every run.

Because the image's `/ms-playwright` already contains the exact matching Chromium build,
`install-playwright-chromium`'s `install chromium` execution (still unconditional, on every OS, same
as before) is expected to be a fast no-op there: Playwright's own installer checks for a completed
install marker in the target browser directory before downloading anything, browser-by-browser, and
skips the download entirely when it's already present at the right revision - confirmed by reading
that check directly in the Playwright Java driver bundle. No skip flag was introduced for this,
since removing an already-harmless step would be needless extra surface for zero benefit; a real CI
run showing that step complete in a second or two (instead of downloading ~290 MiB, as it does on a
bare runner) is the actual confirmation.

The Docker-image verification steps (`docker build --target test .` / `--target robustness .`) are
each their own separate, non-containerized job in `nightly.yml`, rather than trailing steps inside
the Playwright-container jobs: the Playwright Java image doesn't ship a `docker` CLI, so `docker
build` needs to run from a plain runner regardless of what the main verification job uses. Those
Docker builds still run `./mvnw clean verify` using the `mcr.microsoft.com/playwright/java` image's
own default JDK inside the container image (unchanged, pre-existing `Dockerfile` behavior) - that is
a Docker-image build/packaging check, not a substitute for the Java-21-specific CI validation above,
and is out of scope for this fix to change.

**Testcontainers**: `webagent4j-integration-tests` declares a `testcontainers` test-scope dependency,
but no test anywhere in the repository actually imports `org.testcontainers.*` or uses
`@Testcontainers`/`GenericContainer` (verified by grepping the full source tree) - nothing currently
needs a Docker daemon from inside the test JVM. Running the Linux CI job inside a job `container:`
is safe on that basis; if a future test does need Testcontainers, that would need to be revisited
together with whether Docker-in-Docker access is available from a job container on the runners in
use at that time.

### A benign, expected "Playwright Host validation warning" for WebKit

CI logs a `Playwright Host validation warning` naming ~35 missing shared libraries
(`libgtk-4.so.1`, `libgraphene-1.0.so.0`, the `libgst*` GStreamer set, ~13 `libflite*` speech
libraries, `libavif.so.16`, `libmanette-0.2.so.0`, `libGLESv2.so.2`, `libx264.so`, and others) the
first time a test launches a browser (`SensitiveValueObservationIT`), even though `install-deps
chromium` ran moments earlier and every one of the 79 integration tests passes. This is expected,
not a defect, and is not silenced here because the alternative - installing those packages - would
be pure waste for a Chromium-only test suite:

- Playwright's own driver bundle (`coreBundle.js`) keeps a separate, much shorter list of Linux
  packages per browser engine (verified by extracting `driver-bundle-1.60.0.jar` and reading its
  `ubuntu24.04-x64` dependency table directly): Chromium's list has 20 entries and does not contain
  any of the libraries this warning names. Every single one of them instead maps, through the same
  file's own `lib2package` table, to a package that appears only in the **WebKit** dependency list
  (e.g. `libgtk-4.so.1` -> `libgtk-4-1`, `libflite_cmulex.so.1` -> `libflite1`, `libGLESv2.so.2` and
  `libx264.so` are WebKit's own explicit `dlopen` dependencies). This project never launches WebKit.
- The warning's own stack trace (`installBrowsers` -> `Registry.validateHostRequirementsForExecutablesIfNeeded`
  -> `Registry._validateHostRequirements` -> `validateDependenciesLinux`, at `coreBundle.js:64433`)
  shows it comes from the CLI `install` command's own validation step, called internally by the
  Playwright Java driver the first time a browser launches in the JVM - separately from, and in
  addition to, this project's own `install chromium` (`pre-integration-test`, always active) and
  `install-deps chromium` (`ci-playwright-deps` profile) executions. Both of those two explicit,
  project-controlled invocations correctly resolve to Chromium only: `coreBundle.js`'s own
  `resolveBrowsers(["chromium"], ...)` returns only `chromium`, `chromium-headless-shell`, and
  `ffmpeg`, and never touches WebKit's dependency group - confirmed by reading that function
  directly, not inferred. The driver's internal auto-check that produces this warning instead calls
  `resolveBrowsers` with **no** browser name filter, which resolves to *every* download-by-default
  browser (Chromium, Firefox, WebKit, ffmpeg) regardless of which one the test actually launches.
- The warning is caught and only logged (`console.error`, with `e.name` relabeled to "Playwright
  Host validation warning"), never re-thrown, which is why it does not fail the build.
- Because it is WebKit-only and this suite never launches WebKit, the correct fix is to leave it
  documented rather than to install ~35 additional packages (GStreamer, Flite, GTK4, etc.) via
  `sudo apt` for a browser engine nothing here exercises - that would be pure unrequested surface
  area, not a fix for anything actually broken. If a future change adds real WebKit-driven tests,
  extend `ci-playwright-deps`'s `install-deps` invocation to include `webkit` (Playwright's own
  supported mechanism already handles this - no hand-maintained package list needed) rather than
  suppressing the warning.

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

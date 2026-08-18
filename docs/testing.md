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

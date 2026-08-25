# Testing

WebAgent4J's test strategy is designed to prove deterministic contracts without hiding failures through retries or fragile wall-clock assumptions.

## Naming and Maven lifecycle

- unit tests end in `Test` and run under Surefire;
- integration tests end in `IT` and run under Failsafe;
- browser integration fixtures use local loopback servers rather than public websites;
- `./mvnw clean verify` is the standard reactor gate;
- `./mvnw -Probustness verify` adds the profile-gated adversarial corpus.

Do not document exact total test counts in this guide; they change whenever coverage is added and are not a contract.

## Deterministic time

Wait/deadline tests prefer injected `IMonotonicClock`/`IWaitSleeper` fakes. Fake time should exercise the real production arithmetic/polling state machine without making the test sleep for the nominal timeout.

Real browser timing is used only where the browser race itself is the behavior under test. Such tests use bounded, generous windows and assert semantic outcomes rather than microbenchmark timing.

## No test retry as correctness mechanism

A production or CI flake is treated as a defect to reproduce and fix, not as a reason to add a hidden retry. Repeating a deterministic race suite during investigation is evidence gathering; it must not change the pass/fail semantics of the committed test.

## Side-effect oracles

Exactly-once action tests should use an oracle updated synchronously at the action boundary when possible. When a secondary asynchronous server-side signal is needed, poll that read-only signal with a short bounded loop rather than a blind sleep.

Tests must never weaken expected ambiguity/not-found/wrong-target assertions just to accommodate a production regression.

## DOM mutation tests

For T0 -> explicit mutation -> T1 scenarios, mutate deterministically from the test (for example via a named page function) after the T0 assertion. Do not use `setTimeout` when the purpose is merely to create a later state; timer races make T0 timing host-dependent.

A timer is appropriate when the test's purpose is specifically to race a live wait/stability operation against a changing DOM.

## Playwright integration prerequisites

The Playwright-backed modules install the pinned Chromium browser revision during the integration-test lifecycle. Linux CI uses the official matching Playwright Java container so browser host dependencies are preinstalled rather than acquired through a runtime `apt` dependency path.

Java 21 is still explicitly selected in CI because the container's default JDK version is not the project's compatibility baseline.

Windows and macOS standard-reactor verification belong to the nightly platform matrix. Chromium remains the full robustness gate; see [Support matrix](support-matrix.md).

## Security in tests

Fixtures must not contain real credentials, tokens, cookies, personal data, private production URLs, or proprietary page content. Secret-sentinel tests should use synthetic values and assert that forbidden channels do not contain them.

Generated browser failure artifacts belong under ignored `target` directories and must be reviewed before sharing externally.

## Release evidence

Before a release candidate is certified:

1. run standard `clean verify` from a clean exact-head checkout;
2. run the full robustness profile;
3. verify CI, CodeQL, and Dependency Review against the same commit SHA;
4. re-fetch the head immediately before the final verdict;
5. do not merge/release with an unresolved P0/P1 robustness or documentation finding.

See [Release procedure](release.md) and [Hardening](hardening.md).

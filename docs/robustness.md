# Robustness benchmark

WebAgent4J treats a wrong target or duplicate side effect as more serious than a safe failure. The robustness suite verifies that uncertainty remains explicit rather than being converted into a convenient but unjustified action.

## Release invariants

The principal behavioral gates are:

- wrong targets: zero;
- duplicate backend side effects caused by framework retry: zero;
- unexpected opaque backend failures converted to absence/success: zero;
- expected ambiguity remains ambiguity;
- expected unresolvable/not-found cases remain safe failures;
- clean supported semantic/ARIA cases resolve correctly;
- secret sentinels do not appear in prohibited framework-owned channels;
- deterministic resource cleanup holds on success/failure/cancellation paths.

Timings are diagnostic, not a fragile microbenchmark release gate.

## Running

```bash
./mvnw -Probustness verify
```

On Windows:

```powershell
.\mvnw.cmd -Probustness verify
```

The profile includes the dedicated `webagent4j-robustness-tests` module in addition to the 27-module default reactor.

## Corpus discipline

Scenarios are version-controlled local fixtures with stable IDs/expected classifications. Expected outcomes are reviewable data; the suite must never silently rewrite a baseline because production behavior changed.

A proven safe improvement can change an expected safe failure through explicit review. A change from a safe outcome to a wrong target is always a regression.

## Coverage dimensions

The robustness surface includes semantic/ARIA resolution, ambiguity, dynamic replacement/reordering, structured scopes, frames, action exactly-once behavior, wait/deadline boundaries, observation/extraction bounds, HTTP/browser crawler limits/cancellation, workflow fail-fast/secret behavior, recording strictness, and plugin load/runtime boundaries.

Frame-specific cases live beside rather than inside element-only corpus models where the scenario data model is different. Do not distort a fixed corpus schema merely to force unrelated document-boundary scenarios into it.

## Failure artifacts

On failure, local artifacts may include structured locator diagnostics, compact observation, fixture HTML, and screenshot. They remain under ignored build-output directories. They must contain synthetic data only and should be sanitized before external sharing.

## Platform meaning

The full deterministic browser robustness gate is parameterized by the `robustness.browser` test property to cover Chromium, Firefox, and WebKit. `develop` carries nightly-matrix and release-verification infrastructure for all three, and exact-head evidence has observed the complete corpus passing, with zero wrong targets, for all three engines together on the same commit - on Linux. Standard CI/nightly coverage on another operating system, or a browser engine's mere launch-path implementation, does not by itself imply equivalent adversarial qualification for that combination: browser and operating-system qualification are independent axes. See [Support matrix](support-matrix.md#browser-and-robustness-qualification-by-operating-system) for the exact current evidence and its Linux-only scope.

## When the correct result is to stop

Icon-only inaccessible controls, visual-only meaning, semantically identical competing regions, closed shadow roots, and other cases without sufficient machine-readable distinction should remain unresolved/ambiguous. The deterministic engine must not guess from pixels or styling merely to increase a success percentage.

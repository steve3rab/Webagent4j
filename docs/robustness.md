# Robustness benchmark

WebAgent4J treats a wrong action as more serious than a safe failure. The deterministic engine must
return `AMBIGUOUS`, `UNRESOLVABLE`, `NOT_INTERACTABLE`, or `TIMEOUT` when available evidence cannot
support a unique safe target. It does not optimize a marketing resolution score.

## Corpus and quality gates

The `webagent4j-robustness-tests` module contains 100 version-controlled scenarios served by a local
loopback HTTP server. The corpus has 20 clean semantic, 20 ARIA, 15 dynamic, 15 ambiguous, 10 scoped
form, 10 independently verified action, and 10 hostile cases. Stable IDs, descriptions, difficulty,
tags, fixtures, expected outcomes, and expected targets make every case reviewable.

The principal gates are:

- wrong targets: exactly zero;
- unexpected runtime exceptions: exactly zero;
- expected ambiguity detection: 100 percent;
- expected unresolvable rejection: 100 percent;
- clean semantic and standards-based ARIA resolution: 100 percent.

Reports include exact and fuzzy resolution counts, safe failures, action verification, dynamic
re-resolution, mean/median/p95/max durations, and results grouped by difficulty and tag. Timings are
diagnostic and are not a fragile microbenchmark gate.

## Running and reproducing

Run the complete local benchmark with Java 21 or later:

```bash
./mvnw -Probustness verify
```

On Windows use `mvnw.cmd`. Reproduce one corpus case with:

```bash
./mvnw -Probustness -Dscenario=AMBIGUOUS-014 verify
```

Generated files are:

- `webagent4j-robustness-tests/target/robustness-report.md`;
- `webagent4j-robustness-tests/target/robustness-report.json`;
- `webagent4j-robustness-tests/target/robustness-artifacts/{scenario-id}/` on failure.

Failure artifacts contain the classification, locator diagnostics, compact observation, fixture HTML,
and screenshot. They remain under ignored `target` directories and must never contain credentials.

## Coverage beyond the corpus

`WebAgentCoreRobustnessIT` exercises complete login, product-card scoping, dynamic modal, unsafe
semantics, and ambiguous-control journeys across the browser, locator, observation, action, and
verification phases. `SemanticConsistencyIT` checks that observed controls and form relationships can
be resolved through their semantic contracts. Metamorphic tests preserve the expected target across
irrelevant wrappers, generated classes, split text, and decorative icons; semantic mutations verify
that renamed, disabled, or duplicated controls change the outcome safely. Stress smoke tests repeat
observations, resolutions, and page lifecycle operations without relying on sleeps or test retries.

## Baseline policy

`src/test/resources/robustness-baseline.json` records every scenario expectation but no timing. A test
requires the baseline and corpus to contain the same 100 IDs and outcomes. Expectation changes must be
small, explicit review diffs. The suite never silently rewrites the baseline. A change from any safe
outcome to a wrong target is always a regression; a proven correct improvement from ambiguity or no
resolution may be accepted through review.

## CI strategy

Pull requests and pushes run the standard reactor plus a clean semantic robustness subset. The nightly
workflow runs the full deterministic Chromium corpus, the existing OS matrix, the Docker robustness
target, and uploads reports and failure diagnostics. The primary corpus has no Internet, credential,
account, CAPTCHA, transaction, or public-site dependency. External web validation is intentionally not
implemented as a required profile; any future external smoke suite must remain manual, read-only, and
non-blocking.

## Adding a scenario

1. Add a minimal English HTML fixture under `src/test/resources/robustness/{category}`.
2. Add one scenario with a stable ID, description, difficulty, tags, expected status, and target.
3. For an action, call the local `/track?target=...` endpoint and assert target plus execution count.
4. Add the expectation to `robustness-baseline.json` as an explicit reviewable line.
5. Run the scenario alone, then the full profile, and inspect both generated reports.

Fix root causes in the responsible phase. Production code must never know scenario IDs, fixture paths,
or expected benchmark targets.

## When deterministic WebAgent4J should stop

Icon-only controls without accessible information, canvas-only interfaces, purely visual meaning, and
ambiguous contexts with no machine-readable distinction must remain `UNRESOLVABLE` or `AMBIGUOUS`.
The deterministic engine does not infer intent from pixels or guess an action.

A future optional AI strategy may consume explicit `UNRESOLVABLE` or `AMBIGUOUS` outcomes as a
separate fallback seam. It must expose strategy, confidence, reason, and policy and must never silently
convert uncertainty into an action. This phase adds no AI dependency and does not begin extraction.

See [known limitations](limitations.md) and
[ADR 0009](adr/0009-safe-semantic-resolution-policy.md).

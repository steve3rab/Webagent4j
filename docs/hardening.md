# Robustness and adversarial hardening

Phase 1.0-C validates the stabilized 1.0 API candidate under hostile input, timing, lifecycle, and
failure conditions. It adds no product feature, supported API or SPI, Maven coordinate, dependency,
or recording schema field. Java 21 remains the minimum supported runtime; later Java releases are
also supported.

The untouched baseline was `28fbc2a1faa4b5e8850355ca8f6ba33c8420c356`. Its standard
`clean verify` passed 952 Surefire tests, 187 Failsafe tests, 32 architecture rules, generated
Javadoc and examples, and the 70 percent Playwright line-coverage gate at 78.8 percent.

## Closed findings

| ID | Severity | Reproduction | Correction | Status |
| --- | --- | --- | --- | --- |
| HARD-001 | P1 | A retry policy accepted `NaN`/infinite backoff and `Duration#toMillis()` overflow escaped from delay calculation | Require a finite factor and saturate delay conversion and exponential growth while preserving zero delay | Resolved |
| HARD-002 | P1 | A `WaitBudget` crossing the signed `nanoTime()` rollover reported zero remaining time without being expired and could fail to expire | Store the timeout allowance and compare rollover-safe elapsed deltas instead of an absolute saturated deadline | Resolved |
| HARD-003 | P1 | Locator configuration, candidates, results, diagnostics, evidence, explanations, and events accepted `NaN` values | Require every documented unit-interval value to be finite as well as within `[0.0, 1.0]` | Resolved |
| HARD-004 | P1 | Per-strategy locator duration used `Instant.now()` and therefore depended on wall-clock adjustment and host load | Measure elapsed strategy time with the injected monotonic clock; retain wall time only for absolute event timestamps | Resolved |
| HARD-005 | P1 | Playwright candidate, element, and frame-URL inspection could turn a nearly exhausted caller budget into independent 200 ms or 1,600 ms internal windows | Remove comfort floors, divide the actual remaining budget across identity and candidate inspections, and recalculate the remaining share before each frame-URL inspection | Resolved |
| HARD-006 | P1 | The exact `aria-label` scope fast path could return one element without checking a second element whose equal accessible name came from `aria-labelledby` or another supported source | Classify every current accessible-name container match as 0/1/N under the shared caller deadline; use visible text only after proven accessible-name absence and propagate ambiguity or backend failure unchanged | Resolved |

Open P0 findings: **0**. Open P1 findings: **0**. Open P2 findings: **0**. No wrong-target,
duplicate-side-effect, secret-disclosure, resource-ownership, or schema-corruption defect was found.

## Cross-module matrix

| Surface | Adversarial boundary | Evidence and result |
| --- | --- | --- |
| Common and wait | Non-finite factors, conversion overflow, exponential overflow, zero delay, exact deadline, huge timeout, signed clock rollover, interruption | `RetryPolicyTest`, `WaitBudgetTest`, and `WaitEngineTest`; HARD-001 and HARD-002 closed |
| Locator | Invalid finite ranges, ambiguity, stability reset, backend failure, timeout, wrong-target protection, monotonic elapsed time | `LocatorFiniteValueTest`, `LocatorEngineWaitIntegrationTest`, `LocatorAdvancedEngineTest`, and semantic integration tests; HARD-003 and HARD-004 closed |
| Playwright browser and frames | Count/evaluate detachment races, still-present timeouts, failed rechecks, opaque backend failures, dynamic frames, cross-origin frames, cross-source accessible-name ambiguity, budget multiplication, multiple ordered text constraints | `PlaywrightLocatorBackendTest`, `PlaywrightScopeResolverTest`, `PlaywrightFrameScopeResolverTest`, `PlaywrightMixedScopeOrderTest`, `ContextWrongTargetProtectionIT`, `MultipleContainingTextContextIT`, frame integration tests, and `SemanticLocatorIT`; HARD-005 and HARD-006 closed |
| Verification and actions | Shared deadlines, stability, preconditions, dry run, interruption before/after invocation, retry classification, exactly-once execution, secret-safe results | `VerificationSharedBudgetTest`, action pipeline tests, and action integration tests; existing fail-closed contracts preserved |
| Observation and extraction | Interrupted capture, invalid values, bounded collection shapes, conversion/validation failure, redaction, detached elements | Observation and extraction unit suites plus `ExtractionRobustnessIT`; no open P0/P1 finding |
| HTTP crawler | Cycles, redirect/retry limits, malformed links, body bounds, interruption, deterministic frontier order, fetcher failures | `HttpCrawlerTest` and crawler integration coverage; no open P0/P1 finding |
| Browser crawler | Cancellation, page ownership, fail-fast cleanup, cyclic navigation, dynamic pages, action and observation failures | `BrowserCrawlerTest`, `BrowserCrawlerRobustnessIT`, and browser-crawler integration coverage; no open P0/P1 finding |
| Workflow | Preflight failure, fail-fast trace shape, condition/action exceptions, interruption, secret-safe rendering, immutable reuse | Workflow unit suite and `WorkflowRobustnessIT`; no open P0/P1 finding |
| Recording | Duplicate/unknown fields, trailing content, wrong types, invalid result matrices, unknown schema, secret and metadata boundaries | Recording codec, invariant, decoder-safety, and replay tests; schema V1 unchanged |
| Plugins | Empty discovery, provider construction failure, null descriptors/strategies, duplicate IDs, ordering, class-loader and context-loader isolation | `PluginLoaderTest` and `PluginLocatorIT`; all-or-nothing loading preserved |
| API, CLI, examples, and architecture | Public signature drift, dependency direction, Java baseline, Javadoc/examples compilation, safe command failures | API signature tests, 32 `ArchitectureTest` rules, standard reactor verification; no new public production type or method |

## Regression method

Each production correction was first reproduced by a deterministic failing test. The focused tests
then passed after the smallest internal change, followed by the complete affected-module suites.
Timing tests use injected monotonic clocks rather than sleeps. Playwright timeout tests capture the
actual adapter options rather than depending on host speed.

The final review protocol is:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Repeat the timing and browser race suites three times, then repeat `clean verify` from a fresh
worktree at the exact branch head. CI, CodeQL, and dependency review must report that same commit.

## Frozen boundaries

- Supported Java and Maven signatures are unchanged.
- Recording JSON schema V1 is byte-shape compatible; no field, enum, or version was added.
- No runtime or test dependency was added.
- No hidden executor, retry, browser action, or workflow side effect was introduced.
- Playwright candidate identity and inspection work divides the caller's actual remaining budget;
  there is no 200 ms, 1,600 ms, or other comfort floor. Frame URL checks recalculate and divide the
  remaining share before each candidate.
- Structured scopes prove uniqueness across every supported accessible-name source before accepting
  a container. An exact `aria-label` cannot bypass an equal `aria-labelledby` match; visible text is
  used only after proven accessible-name absence, and ambiguity or backend failure never falls back.

See [Cross-module contracts](contracts.md), [API stability policy](api-stability.md),
[Testing](testing.md), and the [robustness benchmark](robustness.md).

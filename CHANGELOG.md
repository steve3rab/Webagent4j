# Changelog

All notable changes to this project will be documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (Recording — Phase 0.9-A)

- New `webagent4j-recording` module: a deterministic, versioned recording that excludes raw
  workflow values, preserves engine-redacted diagnostics, and documents its verbatim metadata
  trust boundary, plus a safe offline replay-verification mechanism. A recording is data, not a
  program - `WorkflowRecording` has no `execute()` method and cannot replay itself; there is
  deliberately **no automatic live replay of browser actions** in this phase. Depends only on
  `webagent4j-workflow` and, internally, `jackson-databind` (never exposed in a public signature).
- Recording model: `RecordingId` (caller-supplied, never randomly generated - mirrors `WorkflowId`),
  `RecordingSchemaVersion` (closed, numbered enum, `V1` only), `WorkflowRecording`,
  `RecordedWorkflowStep` (mirrors `WorkflowStepResult`'s FAILED/SKIPPED/NOT_RUN invariants, plus an
  `ASSIGN`-step-never-carries-an-action check and a general false-condition-outcome-requires-SKIPPED
  check), `RecordedCondition`, `RecordedAction`, `RecordedFailure`.
- `WorkflowRecorder`: stateless capture, `WorkflowResult -> WorkflowRecording`. Raw workflow value
  channels are excluded structurally: it never records `WorkflowInputs`, raw output values,
  `ActionResult#value()`, raw `Throwable` data, or the secret registry, and it preserves diagnostic
  text already redacted by `WorkflowEngine`. Caller/action-supplied identifiers are a separate
  metadata trust boundary and are persisted verbatim.
- Canonical JSON encoding/decoding: `IWorkflowRecordingCodec`, `JsonWorkflowRecordingCodec`,
  `RecordingFormatException`. Encoding is deterministic (fixed field order via manual
  `JsonGenerator` writes, never default POJO ordering), never pretty-printed, never trailing a
  newline; every optional field is always emitted (`null` when absent, never omitted); every enum
  is written by name, never ordinal. Decoding is strict: rejects malformed JSON, a duplicate JSON
  object key at any nesting level (`JsonParser.Feature.STRICT_DUPLICATE_DETECTION`), a missing or
  unknown field, an unsupported `schemaVersion` (no fallback), an invalid enum value, a malformed
  `Instant`, a value of the wrong JSON type, an invariant violation, and trailing content after the
  document - every `RecordingFormatException` message references only a fixed schema field path,
  never the offending raw value. No polymorphic/annotation-driven deserialization anywhere.
- Replay verification: `WorkflowReplayVerifier#verify(WorkflowRecording, WorkflowResult)` is pure
  and synchronous - never re-execution, never a browser/backend call. Never fails fast: every
  mismatch is collected in one deterministic traversal (workflow identity/status, step count, each
  common step in order, missing/extra trailing steps, then the top-level failure).
  `WorkflowReplayResult#matches()` is derived from `mismatches().isEmpty()`, never an independently
  settable field. `RecordingId`, `capturedAt`, `ActionId` (non-semantic correlation metadata), a
  condition's description text, and a failure's `safeMessage`/underlying exception
  type name are deliberately never compared - see [docs/recording.md](docs/recording.md) for the
  full rationale per field. `WorkflowReplayMismatchType`, `WorkflowReplayMismatch`,
  `WorkflowReplayResult`.
- New unit test suites (`webagent4j-recording`): `RecordingIdTest`, `RecordingSchemaVersionTest`,
  `RecordingModelInvariantsTest`, `WorkflowRecorderTest` (REC-001..003, REC-SAFE-001),
  `RecordingSecretSafetyTest` (SEC-REC-001..004), `JsonWorkflowRecordingCodecTest`
  (JSON-001..009 plus additional strictness cases), `WorkflowReplayVerifierTest`
  (REPLAY-001..011).
- New real-Playwright integration test `WorkflowRecordingIT` (`webagent4j-integration-tests`):
  records a real login execution with a secret sentinel, encodes it, asserts the sentinel is
  absent from the JSON, decodes it, executes a second independent real login, and verifies a
  MATCH despite the two executions producing different `ActionId`s.
- New ArchUnit rules in `ArchitectureTest`: `recordingRemainsIndependentFromPlaywright`,
  `recordingRemainsIndependentFromBrowserAndCrawlerModules`, `recordingRemainsIndependentFromPluginApi`,
  `recordingRemainsIndependentFromAiLibraries`.
- New example `WorkflowRecordingExample` (`webagent4j-examples`): records a real login, round-trips
  it through JSON, and verifies a second real login against the decoded recording.
- New `docs/recording.md`; `docs/roadmap.md` splits Phase 0.9 into 0.9-A (this phase) and 0.9-B
  (plugin `ServiceLoader` extension points; persistence and any future live replay remain
  unscoped future candidates, not a promise of 0.9-B); `docs/modules.md`, `docs/public-api.md`,
  `docs/limitations.md`, and `README.md` updated - `webagent4j-recording` graduates from the
  reserved-module list.

### Fixed (Recording — metadata trust boundary)

- Clarified the Phase 0.9-A recording security boundary: raw workflow inputs/outputs, action values,
  observations, diagnostics, and raw exceptions remain excluded, while caller/action-supplied
  metadata identifiers such as `RecordingId` and `ActionId` are explicitly documented as persisted
  verbatim and required to be non-sensitive.
- Added `RecordingMetadataTrustBoundaryTest` (`META-TRUST-001..004`) covering a custom ordinary
  `ActionId`, sensitive-looking `ActionId` text, intentional workflow-secret propagation through a
  custom action's metadata, and caller-supplied `RecordingId` persistence. JSON schema V1 and replay
  semantics are unchanged.

### Fixed (Recording — Phase 0.9-A strict review, round 1)

- **`schemaVersion` integer overflow:** the decoder converted `schemaVersion` via
  `JsonNode#intValue()` alone, which silently truncates a value outside the signed 32-bit range
  (`2^32 + 1`'s low 32 bits equal `1`) rather than rejecting it - an out-of-range value could have
  been accidentally accepted as `RecordingSchemaVersion.V1`. Fixed by requiring
  `JsonNode#canConvertToInt()` before ever calling `intValue()`.
- **Decoder diagnostics could echo external JSON:** an unknown field's own name was included in its
  `RecordingFormatException` message (`"unknown field: " + path + "." + name`, where `name` came
  directly from the untrusted document); the raw Jackson parser exception was attached as a public
  cause on malformed-JSON errors; and an internal domain-validation message was blindly
  concatenated and attached as a cause on invariant-violation errors. All three could leak a
  secret embedded in a malformed recording through `exception.getMessage()`/`getCause()`. Fixed:
  unknown-field messages now name only the parent schema path (`"unknown field under: $.workflow"`);
  no cause is ever attached to a decoder-thrown `RecordingFormatException`; invariant-violation
  messages are now a fixed literal (`"recording invariant violation"`), never a concatenation of
  internal exception text. `RecordingFormatException`'s constructors are now package-private - it is
  a type callers catch, not construct.
- **`WorkflowRecording`/`RecordedWorkflowStep` did not enforce a real fail-fast execution shape:**
  a `COMPLETED` recording could contain a `FAILED` or `NOT_RUN` step; a `FAILED` recording could
  contain multiple `FAILED` steps, or a step succeeding after the `FAILED` one; step IDs could
  repeat; a `SKIPPED` step could carry a `true` condition outcome; a `FAILED` step could carry a
  published output variable name; a `SUCCEEDED` `ACTION` step could omit its action summary. None
  of these traces can come from a real `WorkflowEngine` execution. Fixed by adding cross-step
  invariants (new package-private `RecordingInvariants`, invoked from `WorkflowRecording`'s
  constructor) and two new per-step invariants (in `RecordedWorkflowStep`) - enforced identically
  for direct construction, `WorkflowRecorder` output, and JSON decoding, since all three funnel
  through the same constructors. See [docs/recording.md#recording-validity](docs/recording.md).
- Rewrote `WorkflowReplayVerifierTest`'s `REPLAY-003`/`REPLAY-009`/`REPLAY-010` cases, which had
  mutated a valid recording into one of the now-rejected impossible shapes to isolate a mismatch
  type; they now compare two independently valid executions instead, per the strengthened
  invariants above.
- Added `SchemaVersionRangeTest` (`VERSION-RANGE-001..006`), `RecordingDecoderErrorSafetyTest`
  (`ERR-SAFE-001..006` plus a cause-is-null case), `RecordingModelInvariantsTest`
  (`INV-GLOBAL-001..009`, `INV-STEP-001..007`), and `JsonRecordingInvariantTest`
  (`JSON-INV-001..009`).

### Fixed (Recording — Phase 0.9-A strict review, round 2)

- **Preflight/runtime failure classification was one-directional:** a recording was accepted as
  valid whenever every step happened to be `NOT_RUN`, regardless of whether the overall failure's
  *type* was actually one of the three preflight types - so a runtime type (e.g. `ACTION_FAILED`)
  with no `stepId` was wrongly accepted as if it were preflight-shaped. Fixed: `RecordingInvariants`
  now classifies by `failure.type()` first, then enforces the matching shape in both directions -
  only `MISSING_REQUIRED_INPUT`/`INPUT_TYPE_MISMATCH`/`UNDECLARED_INPUT` may omit a `stepId`, and
  every other type must carry one.
- **A `FAILED` step's own `failure.stepId` could disagree with the step's own `stepId`:** nothing
  previously checked this. Fixed: `RecordedWorkflowStep`'s constructor now rejects a `FAILED` step
  whose `failure.stepId()` is absent or names a different step.
- **The overall failure vs. the FAILED step's own failure was only compared on `type` and
  `actionFailureType`:** since `WorkflowEngine.Session#run` reuses the exact same `WorkflowFailure`
  object for both, they can never legitimately differ in any field within one recording. Fixed:
  `RecordingInvariants` now requires full `RecordedFailure` equality (`type`, `safeMessage`,
  `stepId`, `underlyingTypeName`, `actionFailureType`) between the two - distinct from, and not in
  tension with, `WorkflowReplayVerifier` still ignoring `safeMessage`/`underlyingTypeName` when
  comparing two *separate* executions (see
  [docs/recording.md#full-equality-vs-replay-semantics](docs/recording.md)).
- **`RecordedFailure` did not enforce the `ActionFailureType` taxonomy:** any failure type could
  carry, or omit, an `ActionFailureType`, even though only `ACTION_FAILED` ever can (`ActionResult`'s
  own invariant guarantees one is present exactly when `status != SUCCESS`). Fixed: `RecordedFailure`'s
  compact constructor now requires `ACTION_FAILED` to carry one and forbids every other type from
  carrying one.
- **`ACTION_FAILED` could be recorded with no action summary, or one reporting `SUCCESS`:**
  `RecordedWorkflowStep` did not check that an `ACTION_FAILED` step's action summary is present and
  reports a non-success status. Fixed, alongside enforcing the complete failure-type/step-type/
  action-summary matrix derived from `ActionWorkflowStep#run` (see
  [docs/recording.md#the-failure-type--step-type--action-summary-matrix](docs/recording.md)):
  `MISSING_VARIABLE`/`ACTION_FACTORY_FAILED`/`STEP_EXCEPTION`/`CONDITION_EVALUATION_FAILED` never
  carry a summary; `ACTION_FAILED` always carries one reporting a non-success status; `NULL_OUTPUT`/
  `OUTPUT_TYPE_MISMATCH` always carry one reporting `SUCCESS`; and every failure type except
  `CONDITION_EVALUATION_FAILED` can only occur on an `ACTION` step, never `ASSIGN` (provable today
  from the closed `sealed IWorkflowStep`/`AWorkflowStep` hierarchy, since `AssignWorkflowStep#run`
  never fails on its own).
- **A `SUCCEEDED` `ASSIGN` step could omit its published output variable name:** `AssignWorkflowStep`
  always declares and publishes one. Fixed: `RecordedWorkflowStep` now requires it.
- **`RecordingFixtures.failure(type, stepId)` fabricated an `ActionFailureType` for every failure
  type**, regardless of whether that type could legitimately carry one - masking exactly the missing
  validation above. Removed, and replaced with explicit, per-failure-type, engine-reachable builders
  (`preflightFailure`, `conditionEvaluationFailedFailure`, `missingVariableFailure`,
  `actionFactoryFailedFailure`, `stepExceptionFailure`, `actionFailedFailure`, `nullOutputFailure`,
  `outputTypeMismatchFailure`, plus matching step-shape builders).
- Rewrote `WorkflowReplayVerifierTest`'s `REPLAY-008` to compare two genuine engine executions that
  differ only in an incidental factory-exception message, since mutating a single recording's
  top-level `safeMessage` alone is no longer a constructible (valid) recording under full equality.
- Added `RecordingFailureTaxonomyTest` (`INV-FAIL-PREFLIGHT-001..008`, `INV-FAIL-COHERENCE-001..006`,
  `INV-FAIL-ACTION-001..006`, `INV-TAX-001..009`, `INV-ACTION-001..011`, `INV-ASSIGN-001..006`),
  `JSON-TAX-001..006`/`JSON-ACTION-001..003`/`JSON-ASSIGN-001` in `JsonRecordingInvariantTest`, and
  engine-backed `REC-FAIL-001..010` in `WorkflowRecorderTest` - one real `WorkflowEngine.execute()`
  per `WorkflowFailureType`, recorded via `WorkflowRecorder`, proving the strengthened model is not
  over-tight for any state the current engine can actually produce.

### Added (Workflows — Phase 0.8)

- New `webagent4j-workflow` module: a deterministic, sequential orchestration layer over
  `webagent4j-action`. `Workflow`/`Workflow.Builder` (immutable, reusable definitions; building
  performs structural validation only, never a side effect), `WorkflowEngine` (stateless; one
  private, isolated session per `execute()` call), `WorkflowId`/`WorkflowStepId`.
- Typed, write-once variables: `WorkflowVariable<T>` (`publicValue`/`secret` factories - no
  `Map<String, Object>` anywhere in the public API), `IWorkflowVariables`,
  `WorkflowVariableMissingException`, `WorkflowInputs`/`WorkflowInputs.Builder`, `WorkflowOutputs`.
  Required inputs are validated before step 0 runs; optional inputs have no implicit default.
- Masked secret variables with a centralized, single-point redaction contract
  (`SecretRedactor`, internal): explicit typed retrieval always returns the real value; every
  incidental framework-owned rendering (`toString()` on every public workflow type, every condition
  `describe()`) masks a secret as `***`, longest-first for overlapping secret values. No public
  result type exposes an arbitrary raw `Throwable`.
- A small, fixed set of fail-closed declarative conditions: `IWorkflowCondition`,
  `WorkflowConditions` (`exists`, `notExists`, `equals`, `notEquals`, `isTrue`, `isFalse`, `not`,
  `allOf`, `anyOf`) - never an arbitrary `Predicate` or an expression language.
- Real action-pipeline integration through single-use preparation factories:
  `IWorkflowActionFactory<R>` is invoked at most once per execution, only when a step actually
  runs, and only ever calls `IPreparedAction#execute()` once - no `IActionPlan` is ever cached
  inside a workflow definition. `WorkflowSteps.action`/`WorkflowSteps.assign` are the only ways to
  create a step; `WorkflowActionSummary` safely projects an `ActionResult` without leaking its raw
  value, observations, or cause.
- Fail-fast-only sequential execution: `WorkflowStatus`/`WorkflowStepStatus`
  (`SUCCEEDED`/`SKIPPED`/`FAILED`/`NOT_RUN`), `WorkflowFailureType`/`WorkflowFailure`,
  `WorkflowStepResult`/`WorkflowResult`, `WorkflowFailedException`. The first failed step stops
  execution immediately; no workflow-level retry, no workflow-wide timeout, no cancellation in this
  phase.
- Everything runs synchronously on the calling thread - no `ExecutorService`, no
  `CompletableFuture`, no parallelism anywhere in the engine.
- New unit test suite (`webagent4j-workflow`: structural validation, condition semantics, secret
  masking/redaction, and action-factory integration using fakes), real-Playwright
  `WorkflowLoginIT` and `WorkflowRobustnessIT` suites (`webagent4j-integration-tests`, reusing the
  existing `/login` -> `/dashboard` fixture), three new ArchUnit rules
  (`workflowRemainsIndependentFromPlaywright`, `workflowRemainsIndependentFromAiLibraries`,
  `workflowRemainsIndependentFromBrowserAndCrawlerModules`), and a new `WorkflowLoginExample`.
- Depends only on `webagent4j-action` - never Playwright, the browser crawler, or the HTTP crawler
  directly. See [docs/workflow.md](docs/workflow.md) for the full architecture, the complete
  secret-masking contract, and this phase's documented limitations.
- Updated `docs/roadmap.md`, `docs/modules.md`, `docs/public-api.md`, and `README.md` to reflect
  Phase 0.8.
- Hardened after strict review: `IWorkflowStep` is now `sealed` with no custom-implementation
  extension point, eliminating a latent `ClassCastException` risk from the prior open-interface
  design; `IWorkflowCondition` remains a trusted Java extension point but is now handled fully
  defensively by `WorkflowEngine` (a throwing/null `describe()` or `referencedVariables()` never
  escapes as a raw exception, and `referencedVariables()` null/throwing/containing-null is rejected
  at build time). `WorkflowInputs.Builder#put` now rejects re-supplying an already-provided input
  name (write-once is fully enforced, not just documented); `Workflow.Builder#build()` rejects a
  duplicate required/optional input declaration and a `WorkflowInputs` entry naming an undeclared
  input (`WorkflowFailureType.UNDECLARED_INPUT`) fails before step 0. `WorkflowInputs`/
  `WorkflowOutputs#toString()` now redact a known secret's raw text everywhere it appears, including
  inside an unrelated public field's value, not just the field declared secret; redaction always
  happens before message bounding/truncation.
- Second strict-review pass fixed two further secret-safety gaps: `WorkflowOutputs`' safe rendering
  is now computed once, when `WorkflowEngine` assembles the final `WorkflowResult`, against every
  secret known to that execution up to that point - including secret **inputs**, not only this
  container's own secret outputs, and including a secret revealed by a *later* step, so an earlier
  public output containing that same text is still masked. `WorkflowInputs`/`WorkflowOutputs`
  rendering, and a built-in condition's own literal comparison text, are now redacted *before*
  bounding rather than after in every case, closing a boundary case where a secret straddling the
  200-character truncation limit could leak a partial fragment. Added a `WorkflowStepResult`
  invariant requiring a `SKIPPED` result to carry its condition outcome, and 9 new regression tests
  covering both fixes.
- Third strict-review pass fixed a remaining condition-result leak: a `SKIPPED`/`SUCCEEDED` step's
  `WorkflowConditionResult` used to be redacted and bounded immediately when its condition was
  evaluated, so a secret a *later* step went on to reveal could never retroactively mask an
  *earlier* condition's already-recorded description. `WorkflowEngine` now captures a condition's
  description once, at evaluation time, but keeps it as unredacted, unbounded internal state until
  the workflow terminates (`COMPLETED` or `FAILED`), then redacts it against every secret known by
  then and bounds it - mirroring the fix already applied to `WorkflowOutputs`. `condition.describe()`
  is still called at most once per evaluated condition either way. Added 5 new regression tests.
- Fourth strict-review pass fixed a fail-closed contract bug in the built-in condition combinators:
  `WorkflowConditions.not`/`allOf`/`anyOf` converted a wrapped custom condition's malformed `null`
  `describe()` result into the literal text `"null"` through ordinary Java string concatenation and
  `StringBuilder` appending, bypassing `WorkflowEngine`'s documented null-description fail-closed
  path. Composite descriptions now propagate a `null` child description unchanged (never as literal
  text), stop composing further children as soon as one is malformed, invoke each child's
  `describe()` at most once, and let a wrapped child's thrown `RuntimeException` propagate to
  `WorkflowEngine`'s existing defensive boundary unchanged. Added 9 new regression tests covering
  `not`/`allOf`/`anyOf` with a null or throwing child description, both directly and through a full
  workflow execution.

### Added (Browser Crawler — Phase 0.7)

- New `webagent4j-browser-crawler` module: a deterministic, single-lane browser crawler.
  `IBrowserCrawler`/`BrowserCrawler` (the sole implementation), `BrowserCrawlRequest` (immutable,
  builder, fully validated), `BrowserCrawlResult`/`BrowserCrawledPage`/`BrowserCrawlFailure`/
  `BrowserCrawlStatistics`, `BrowserCrawlFailureType`, `BrowserCrawlTerminationReason`,
  `FrameCrawlPolicy` (only `TOP_LEVEL_ONLY` implemented in this phase), and `CancellationToken` (a
  new, minimal cooperative-cancellation primitive - none existed anywhere in the codebase before).
  Depends only on backend-neutral contracts (`webagent4j-browser-api`, `webagent4j-crawler-api`,
  `webagent4j-wait`) - never Playwright directly, enforced by new ArchUnit rules
  (`browserCrawlerRemainsIndependentFromPlaywright`, `browserCrawlerRemainsIndependentFromAiLibraries`).
- One `IBrowser` instance is the crawl session (cookies/storage/auth state shared across every page
  it opens); the crawler creates and always closes its own crawler-owned page but never closes a
  caller-supplied browser unless `closeBrowserOnCompletion(true)` is set.
- Single navigation lane: every backend call (`IBrowser#newPage()`, every `IPage` operation) runs on
  the one thread that calls `crawl(...)` - `maxConcurrency` must be exactly `1` and is rejected
  otherwise. This replaces an earlier worker-pool design that navigated concurrently through
  per-thread `IPage`s sharing one `IBrowser`; that violated `IBrowser`/`IPage`'s own documented
  "not thread-safe" contract and, under real Playwright, silently lost a discovered page from the
  committed result (caught by `BrowserCrawlerIT` on real CI, not by mocks). Determinism of result
  ordering is now structural, not merely a scheduling guarantee. Claiming (dedup + `maxPages`)
  happens through one gate that stays exact regardless.
- New real-Playwright adversarial suite `BrowserCrawlerRobustnessIT` (BC-ROB-001..014): cyclic
  graphs, duplicate fan-out, normalization dedup, exact `maxPages`/`maxDepth` bounds, cancellation/
  failFast resource cleanup, unreachable-backend failures, stability timeouts, dynamic-DOM discovery
  boundaries, out-of-scope links/redirects, and deterministic repeated runs.
- Page stability reuses `webagent4j-wait`'s `WaitEngine`/`WaitPolicy.stableFor` (a DOM-fingerprint
  probe) - no `Thread.sleep`, no second timing implementation. Link discovery reuses
  `IPage.observe()`'s already browser-resolved `href` values - no raw HTML parsing.
- New unit tests (`webagent4j-browser-crawler`), real-Playwright `BrowserCrawlerIT` and
  `BrowserCrawlerRobustnessIT` suites (`webagent4j-integration-tests`), and two ArchUnit rules. See
  [docs/browser-crawler.md](docs/browser-crawler.md) for the full contract, determinism guarantee,
  and this phase's documented limitations (single navigation lane, top-level frames only, no SPA
  `pushState` tracking, no redirect hop list).
- Updated `docs/crawler.md`, `docs/modules.md`, `docs/architecture.md`, `docs/roadmap.md`,
  `docs/index.md`, `docs/public-api.md`, `docs/limitations.md`, and `README.md` to reflect Phase 0.7.

### Fixed (Browser Crawler — Phase 0.7 correction round)

- `navigationTimeout` is now the real, authoritative bound on a navigation attempt, not merely a
  client-side check performed after a call a backend's own (longer) default could already have
  bounded. New backend-neutral `IPage#navigate(String, Duration)` (default method fails explicitly
  with `UnsupportedOperationException` for a backend that cannot honor a caller-supplied timeout,
  rather than silently falling back to `navigate(String)` and pretending the timeout was enforced;
  the Playwright adapter overrides it and maps the timeout to the native driver's per-call navigation
  timeout, translating its native `TimeoutError` to the new backend-neutral
  `NavigationTimeoutException`). `BrowserCrawler` now passes the remaining `WaitBudget` into every
  navigation call and classifies that typed exception directly to `NAVIGATION_TIMEOUT` - never
  inferred from `WaitBudget.expired()`'s timing or a backend-specific exception message.
- An observation that hits its configured capture limit (`ObservationStatistics#truncated()`) is no
  longer recorded as a complete, successful page - it becomes a new `BrowserCrawlFailureType
  .OBSERVATION_TRUNCATED` failure instead, so a link past the retained boundary is never silently
  missed.
- Cancellation now centrally blocks every new claim (seed or discovered child), not only new
  navigation - closing a gap where an already-cancelled crawl could still claim its seeds, and a
  cancellation observed mid-crawl could still let already-in-flight discovery keep expanding the
  frontier. New `CrawlDecisionType.REJECT_CANCELLED` (`webagent4j-crawler-api`) records a
  cancelled-out discovered link with the same honesty as any other rejection reason.
- Corrected `BrowserCrawlRequest`/`docs/browser-crawler.md` wording that implied the crawler creates
  or guarantees session isolation - the caller-supplied `IBrowser` is the session boundary, and
  isolating one crawl's session from another (or from other automation on the same instance) is the
  caller's responsibility.
- The stability fingerprint now also digests every anchor's `href` in document order, so a
  link-target-only mutation with a constant element count no longer goes unnoticed - a real gap a
  count-only fingerprint could not see (common in SPA hydration rewriting `href` attributes in
  place). Corrected in a follow-up (see below) to a `JSON.stringify`-encoded array (not a
  delimiter-joined string) capped at 2000 anchors, plus a separate total-anchor-count term.

### Fixed (Browser Crawler — Phase 0.7 second correction round)

- The `IPage#navigate(String, Duration)` default no longer silently falls back to `navigate(String)`
  (which would have let a backend quietly ignore the caller's timeout) - it now throws
  `UnsupportedOperationException` explicitly, after validating the timeout argument itself. New
  backend-neutral `io.webagent4j.browser.NavigationTimeoutException`; the Playwright adapter
  translates its native `TimeoutError` to this type, and `BrowserCrawler` classifies it directly to
  `NAVIGATION_TIMEOUT` with no `WaitBudget.expired()` inference or exception-message parsing
  involved.
- The stability fingerprint's href digest is now `JSON.stringify`-encoded rather than
  `"|"`-delimiter-joined, so an href that itself contains the delimiter cannot collide with a
  genuinely different href sequence; it also now includes a separate total-href-bearing-anchor-count
  term, and its cap is aligned to exactly the 2000-element `maxElements` bound the engine's own
  discovery observation already uses, rather than an independent, smaller 500-anchor limit.
- `BrowserCrawlerRobustnessIT`'s BC-ROB-016 (cancellation during an in-flight navigation) no longer
  coordinates via `Thread.sleep` on either side - the fixture now signals a `CountDownLatch` the
  instant the request arrives and blocks on a second latch the test releases only after `cancel()`
  has already been called, making the race deterministic by construction instead of by timing
  margin. This also fixed a real defect: the previous version ran the crawl itself on a second
  thread from the one that launched the browser, violating this engine's own single-execution-lane
  requirement and hanging real CI for over 20 minutes instead of failing fast.
- New STABILITY-002 test proving an href containing a literal `"|"` character cannot collide with a
  differently-partitioned `"|"` sequence from a sibling anchor.
- Corrected the PR description's and `docs/browser-crawler.md`'s claim of "no existing Phase 0.1-0.6
  public API changed" - two additive, backward-compatible changes were introduced: `IPage` gained
  `navigate(String, Duration)` and `NavigationTimeoutException`; `CrawlDecisionType`
  (`webagent4j-crawler-api`) gained `REJECT_CANCELLED`. No existing method signature changed or type
  was removed.
- New `BrowserCrawlerRobustnessIT` scenarios BC-ROB-015/016 (href-only mutation stability, real
  in-flight-navigation cancellation) plus new `BrowserCrawlerIT`/`BrowserCrawlerTest` coverage for
  the timeout and observation-truncation fixes.

### Fixed (Browser Crawler — Phase 0.7 third correction round: bounded stability)

- **The actual production gap, not just its symptom.** The second correction round's fix for a
  30-minute CI hang replaced a client-side meta-refresh fixture with an HTTP 302 redirect, which
  made CI green but did not touch the underlying defect: `PageStabilityWaiter` polled
  `IPage#evaluate(String)` from a `webagent4j-wait` `WaitEngine` loop, and that loop can only check
  its budget *between* probe calls - a single `evaluate()` call in flight during a client-side
  navigation transition could hang indefinitely, with no exception and no timeout, regardless of
  `navigationTimeout`. This round fixes the actual gap: `PageStabilityWaiter` now expresses the
  entire stability condition as one JavaScript predicate and hands it to a new backend-neutral
  `IPage#waitForCondition(String, Duration)`, which the Playwright adapter maps directly onto
  `Page#waitForFunction` - a native, driver-enforced, timeout-bounded polling primitive that
  transparently continues polling in a newly-navigated execution context instead of hanging. There
  is exactly one call from Java into the backend per stability wait, and that call - never a loop
  wrapped around it - is what the backend itself bounds.
- New backend-neutral `io.webagent4j.browser.ConditionTimeoutException`
  (`webagent4j-browser-api`), mirroring `NavigationTimeoutException`: the Playwright adapter
  translates its native `TimeoutError` to this type when `waitForFunction` never becomes truthy in
  time, and `BrowserCrawler` classifies it directly to `PAGE_STABILITY_TIMEOUT`.
- The real client-side-navigation-during-stability regression this design fixes is proven directly:
  `BrowserCrawlerRobustnessIT` restores a genuine meta-refresh reproducer (rather than the HTTP
  302 substitute the previous round left in its place) and asserts bounded, structured behavior -
  success or an explicit failure, never a hang - under real Playwright.
- `BrowserCrawledPage#stabilityElapsed` renamed to `timeToStability`: the field was always populated
  from the shared `WaitBudget#elapsed()`, which starts before `navigate()` is called - i.e. combined
  navigation-plus-stability elapsed time, not stability-only, despite its old name.
- `<area href>` (image-map links) are now discovered the same as `<a href>`: the Playwright
  observation backend's element selector, role inference, and `href-resolved` computation all cover
  `area[href]`, with a targeted visibility-check exemption (`<area>` carries `display: none` in the
  HTML default UA stylesheet despite being a genuinely clickable hotspot, which would otherwise make
  every `<area>` read as permanently invisible). The stability fingerprint's link digest also now
  covers `area[href]`, consistent with discovery.
- Corrected `docs/browser-crawler.md`'s stability-fingerprint claim that its 2000-link digest cap is
  identical to `ObservationOptions.maxElements(2000)`'s retained set - they are independently chosen
  bounds over different underlying sets (all links vs. all semantic elements of any kind) and are not
  guaranteed to agree element-for-element.
- Documented, honestly, the precise scope of what `navigationTimeout` bounds: navigation and
  stability are now both backend-natively bounded, but `page.url()`, `page.observe(...)`, and
  `page.title()` - called after stability succeeds, to assemble the result - are not covered by any
  further deadline. This was true before this round too; it was simply undocumented.
- Removed stale Javadoc/comments describing the earlier abandoned Java-side polling-loop stability
  design as current.

### Fixed (Browser Crawler — Phase 0.7 fourth correction round: strict code review)

- **`IPage#waitForCondition` no longer requires an unbounded call after its own bounded wait.** The
  third round's Playwright adapter called `Page#waitForFunction` (bounded) but then called the
  returned `JSHandle`'s `jsonValue()` and `dispose()` (neither bounded by anything) to satisfy a
  return value nothing actually used. `waitForCondition`'s signature is now `void` - callers only
  ever needed "did it stabilize in time," never the JavaScript predicate's own value - so the
  Playwright adapter drops the handle without an extra round-trip: it is reclaimed automatically
  once its execution context is destroyed, which for a per-navigation condition like this one
  happens by the very next `navigate()` at the latest.
- **`ConditionTimeoutException`'s typed provenance now survives into `BrowserCrawlFailure.cause()`.**
  `PageStabilityWaiter#awaitStable` no longer catches and reclassifies its own timeout into a
  cause-less `WaitResult`; it now either returns normally (success) or lets `ConditionTimeoutException`
  propagate - synthesizing its own instance, with no cause, only for the case where the shared
  budget was already exhausted before a backend call could even be attempted. `BrowserCrawler`
  catches the typed exception directly (before its generic `RuntimeException` handler) and preserves
  it as the failure's cause.
- The stability predicate now tracks elapsed time with the page's own monotonic `performance.now()`,
  never wall-clock `Date.now()`.
- `IPage#navigate`/`waitForCondition` (and `BrowserCrawlRequest.navigationTimeout`/`stabilityWindow`)
  now reject a positive-but-sub-millisecond `Duration` explicitly (`IPage#requirePositiveMillisTimeout`)
  instead of silently flooring it to 1ms via `Math.max(1, ...)` - both ultimately resolve to a
  millisecond-valued backend option, so a caller asking for less than that can never be honestly
  honored. `BrowserCrawlRequest` additionally rejects a `stabilityWindow` longer than
  `navigationTimeout` at `build()`, since the two share one budget.
- **`<area href>` links now carry `LinkKind.AREA` through the whole discovery pipeline**, not
  `LinkKind.ANCHOR`: new `RawLink#kind()` field, populated by `LinkDiscoverer` from the source
  element's tag (`<a>` → `ANCHOR`, `<area>` → `AREA`; an unexpected link-role element sourced from
  neither is skipped rather than assigned an invented kind), and threaded unchanged through
  `BrowserCrawler#toDiscoveredLink` on every decision path (accepted, out-of-scope, duplicate,
  max-depth, max-pages, cancelled). A rejected seed - which never originates from an HTML element -
  still uses `LinkKind.ANCHOR` as a documented convention, not a provenance claim.
- New real-Playwright `BrowserCrawlerRobustnessIT` scenarios BC-ROB-018/019/020 (AREA-IT-001..003):
  a root-relative `<area href>` discovered with `LinkKind.AREA` and actually navigated, a
  dot-relative `<area href>` resolved by the browser's own `href-resolved`, and an out-of-scope
  `<area href>` discovered but never navigated, correctly rejected.
- Removed further stale Javadoc implying navigation-order/statistics determinism holds only "despite"
  concurrent completion timing - this engine has no physical navigation concurrency to be despite of;
  the determinism is structural.
- Reworded the "all backward-compatible" compatibility claim: `CrawlDecisionType.REJECT_CANCELLED` is
  additive and breaks no existing method signature or type, but a downstream consumer's own
  exhaustive `switch` over that enum would need updating to handle the new constant - a normal,
  expected consequence of an additive enum change, called out explicitly rather than folded into a
  blanket compatibility claim.

### Fixed (Browser Crawler — Phase 0.7 fifth correction round: timeout precision and thread-safety proof)

- **Whole-millisecond timeout precision, not merely "at least 1ms".** The fourth round's timeout
  validator rejected a sub-millisecond `Duration` (checking `toMillis() < 1`) but missed a distinct
  bug: `Duration.toMillis()` truncates rather than rounds, so a positive, at-least-1ms `Duration`
  carrying a sub-millisecond remainder (`Duration.ofNanos(1_500_000)`, 1.5ms) passed that check and
  was silently truncated to 1ms - a bound the caller never asked for. `IPage`'s validator, `Playwright
  Page`'s duplicated copy, and `BrowserCrawlRequest`'s `navigationTimeout`/`stabilityWindow`
  validation now all additionally reject any positive `Duration` whose `getNano() % 1_000_000 != 0`,
  with a distinct message from the "at least 1ms" rejection. `Duration#getNano()` is used rather than
  `Duration#toNanos()` for this check specifically to stay overflow-safe for an arbitrarily large
  `Duration`.
- **The timeout validator is no longer public API.** `IPage#requirePositiveMillisTimeout` was a
  `public static` method on a public interface - an implementation detail that should never have
  become public surface merely because two default methods shared it. It is now `private static`
  (Java 21 interface private static methods) and renamed `requireWholeMillisecondTimeout` to reflect
  the stricter rule. Since the Playwright adapter could no longer call it externally, it gained its
  own private, byte-for-byte-identical copy rather than a new shared public utility - duplication of
  a small private helper is preferred here over expanding public surface for it.
- **`everyBackendCallHappensOnTheSingleCallingThread` now instruments every backend call
  `BrowserCrawler` actually makes** on its success path - `IBrowser#newPage()`, `IPage#navigate`,
  `IPage#waitForCondition`, `IPage#url()`, `IPage#observe()`, `IPage#title()`, `IPage#close()` - not
  only `navigate()`, and now asserts both that every recorded call landed on the calling thread and
  that every required operation was actually observed, rather than passing vacuously on partial
  instrumentation.
- **Softened two previously overclaiming Playwright-behavior claims** (`PlaywrightPage#waitForCondition`
  Javadoc, `PageStabilityWaiter`'s class Javadoc, and `docs/browser-crawler.md`'s Stability section):
  what was written as "Playwright transparently continues polling in a newly-navigated execution
  context" is now split into the actual architectural guarantee this design depends on
  (`Page#waitForFunction` carries its own native timeout, so the call cannot hang past `timeout`
  regardless of what happens to the execution context) and a separately-labeled empirical observation
  specific to the pinned Playwright version (1.60.0), proven only by `BrowserCrawlerRobustnessIT`'s
  real meta-refresh regression test, never asserted as a documented, versioned Playwright API
  contract. Also softened an unverified claim about exactly when Playwright reclaims a dropped
  `JSHandle` reference to simply noting WebAgent4j does not retain it.
- **CI-caught regression from the fix above, fixed in a follow-up commit on the same branch:**
  `BrowserCrawler`/`PageStabilityWaiter` pass `WaitBudget#remaining()` - a `Duration` computed from a
  live monotonic clock, essentially never an exact whole millisecond - directly into
  `IPage#navigate`/`waitForCondition`. The new whole-millisecond-precision validator rejected it on
  every real navigation, not just the fractional-literal cases it was written for (caught by real CI's
  `Java 21 / Linux` job across 28 integration/robustness tests, never reproduced by the mock-based unit
  suite). Both call sites now floor the computed remaining budget to whole milliseconds immediately
  before handing it to the backend-bounded call - flooring can only shorten the bound actually honored,
  never exceed the real remaining time.

### Fixed (Browser Crawler — Phase 0.7 sixth correction round: `timeToStability` measurement point)

- **`BrowserCrawledPage#timeToStability` was captured after, not before, the post-stability
  calls its own Javadoc says it excludes.** `budget.elapsed()` was read only once, right before
  constructing the successful `NavigationSuccess` outcome - by that point `page.url()`,
  `page.observe(...)`, and `page.title()` had already run, silently inflating `timeToStability` by
  however long those three calls took, contradicting the documented contract ("excludes any time
  spent afterward in `page.url()`/`page.observe()`/`page.title()`") and the
  `timeToStability <= navigationTimeout` invariant for a page whose post-stability calls happened to
  be slow. Fixed: `budget.elapsed()` is now captured exactly once, immediately after
  `stabilityWaiter.awaitStable(...)` returns successfully and before `page.url()` is ever called;
  `WaitBudget` itself is unchanged - only the read point moved.
- **`BrowserCrawledPage`'s Javadoc no longer implies `page.url()`/`page.observe()`/`page.title()`
  are one atomic snapshot.** They are three separate, sequential backend calls made immediately
  after stability is accepted; a new class-level note states there is a small window - between
  stability acceptance and these calls, and between the calls themselves - during which the page
  could theoretically mutate or navigate again, and that Phase 0.7 does not provide an atomic
  cross-call snapshot. `title`'s and `links()`'s per-field Javadoc were reworded from implying an
  atomic "at the moment of stability" read to "read/discovered immediately after accepted
  stability."
- New deterministic regression test,
  `BrowserCrawlerTest.timeToStabilityExcludesPostStabilityObservationAndMetadataCalls`, using the
  suite's existing fake monotonic clock: navigation and stability together advance it by 600ms,
  then `page.url()`/`page.observe()`/`page.title()` advance it by a further 2.4s: `timeToStability`
  is asserted to be exactly 600ms, proving the 2.4s spent afterward never reaches it, and that it
  remains `<= navigationTimeout`.

### Added (Public API documentation consolidation)

- New `docs/public-api.md`: a comprehensive public API reference spanning every implemented module
  (0.1–0.6) - a module-selection table, the API layering diagram, core principles (determinism,
  fail-closed behavior, structured results, backend-neutrality, live re-resolution), a per-domain
  section (Browser, Locators, Wait and stability, Observation, Actions, Verification, Extraction,
  HTTP Crawler) with entry points and minimal examples, an error/result-semantics table across every
  domain, an explicit determinism contract (logical determinism vs. environment-dependent values),
  a fail-closed contract, an API-stability statement, and the API-module-vs-implementation-module
  split with minimal Maven snippets.
- New `package-info.java` for the primary public packages (`io.webagent4j.core`,
  `io.webagent4j.browser`, `io.webagent4j.locator.api`, `io.webagent4j.wait`,
  `io.webagent4j.action`, `io.webagent4j.verification`, `io.webagent4j.extraction.api`,
  `io.webagent4j.crawler.api`, `io.webagent4j.crawler`, `io.webagent4j.observation`,
  `io.webagent4j.dom`), each documenting the package's responsibility, entry points, and relation to
  its neighboring module - none existed before.
- `docs/index.md` and `README.md` now link to the Public API reference as a primary entry point.

### Added (GitHub Pages Javadoc publishing)

- The aggregated Javadoc is now published through GitHub Pages on every push to `main`
  (`.github/workflows/pages.yml`), served under `api/latest/` alongside a small static landing page
  (`docs-site/`). `README.md`, `docs/index.md`, and `docs/public-api.md` link to it.

### Fixed (documentation drift found during the public API audit)

- `docs/modules.md` described `webagent4j-testing` as an implemented "Shared test-fixture boundary."
  It currently has no source code at all (only its `pom.xml`) - corrected to say so explicitly, and
  it is no longer listed as a usable module in `docs/public-api.md`.

### Added (HTTP Crawler — Phase 0.6)

- New `webagent4j-crawler-api` module (backend-neutral, depends only on `webagent4j-common`):
  `CrawlRequest` (immutable, builder, fully validated at construction - a bad `maxDepth`,
  `maxPages`, timeout, `maxResponseBytes`, `maxRedirects`, non-HTTP(S) seed, or
  `TraversalStrategy.DEPTH_FIRST` never surfaces only mid-crawl), `CrawlResult`, `CrawledPage`
  (immutable, every collection defensively copied), `CrawlFailure`/`CrawlFailureType` (a structured
  taxonomy covering network, timeout, redirect, HTTP-status, crawl-limit, duplicate-fetch, content,
  and backend failures, including `BACKEND_FAILURE` for an opaque fetcher exception - never silently
  reclassified as another type), `CrawlStatistics`, `CrawlTerminationReason`, `DiscoveredLink`, `CrawlDecision`/
  `CrawlDecisionType`, `RedirectHop`, `CrawlPageProvenance`, `QueryParameterPolicy`
  (`KEEP_ALL`/`DROP_ALL`/`DROP_KNOWN_TRACKING`, conservative by design - only the five standard
  `utm_*` parameters), `TraversalStrategy`, `LinkKind`, and the `ICrawler`/`IUrlNormalizer`/
  `ICrawlScopePolicy`/`ICrawlDeduplicator` ports.
- `webagent4j-crawler` graduates from a reserved, empty module to the deterministic, sequential
  HTTP crawler engine, with an entirely new dependency set (`webagent4j-common`,
  `webagent4j-crawler-api`, `webagent4j-wait`, jsoup, slf4j-api) replacing its old reservation
  toward `webagent4j-http`/`webagent4j-storage` (both remain reserved and untouched -
  `IHttpFetcher`/`JavaHttpFetcher` live directly in `webagent4j-crawler`, since nothing else
  currently needs a standalone HTTP transport module): `HttpCrawler` (the orchestrator - one
  `crawl(CrawlRequest)` call, no concurrency, no `Thread.sleep`, backoff delegated to the injected
  `IWaitSleeper` reused from `webagent4j-wait`), `JavaHttpFetcher` (`java.net.http.HttpClient`
  adapter, `HttpClient.Redirect.NEVER` - `HttpCrawler` follows every redirect hop itself and
  scope-checks each target before following it, so an out-of-scope redirect target is never
  silently fetched), `JsoupHtmlLinkExtractor` (a real, tolerant HTML parser - never a regular
  expression - extracting `<a href>`/`<area href>`, title, and declared canonical URL, with
  correct `<base href>` resolution and RFC 3986-correct empty-href handling), `DefaultUrlNormalizer`
  (deterministic and idempotent - proven by a parameterized test - lowercases scheme/host, drops
  the fragment and default port, resolves dot segments, never re-encodes already-percent-encoded
  paths), `HostScopePolicy` (dot-boundary-aware subdomain matching - `evil-example.com` is never
  wrongly accepted as a subdomain of `example.com` - and independent per-seed allowed-host roots
  for multi-seed crawls), plus internal `BreadthFirstCrawlFrontier` (a plain FIFO queue is already
  correct BFS order here), `InMemoryCrawlDeduplicator` (normalized-URL identity;
  `CrawlRequest#maxPages()` bounds URLs *claimed*, checked proactively before every claim, not just
  successful pages - a mostly-404 site cannot bypass the limit), and `HttpResponseClassifier`.
  `JavaHttpFetcher`'s bounded body subscriber enforces `maxResponseBytes` while streaming, never
  after fully buffering the response - exceeding it raises `ResponseTooLargeException`
  (`RESPONSE_TOO_LARGE`), never an `OutOfMemoryError` or a silently truncated success.
- Reuses `io.webagent4j.common.RetryPolicy` directly as `CrawlRequest#retryPolicy()`'s type
  (rather than inventing a crawler-specific record) and `io.webagent4j.wait.IWaitSleeper` for retry
  backoff (rather than `Thread.sleep`), per the existing project convention of checking for an
  equivalent before adding a new type.
- 3 new ArchUnit rules (`ArchitectureTest`): the crawler modules stay independent from Playwright,
  stay independent from AI/LLM libraries, and `webagent4j-crawler-api` stays independent from the
  crawler engine module (only the reverse dependency direction is allowed).
- 3 new example programs in `webagent4j-examples`: `HttpCrawlSimpleExample`,
  `HttpCrawlRestrictedExample` (scope restriction and rejection diagnostics),
  `HttpCrawlDiagnosticsExample` (full `CrawlStatistics` and structured failures).
- New `docs/http-crawler.md`; updated `docs/crawler.md` (no longer describes unimplemented
  features), `docs/roadmap.md`, `docs/modules.md`, `docs/architecture.md`, `docs/limitations.md`,
  `docs/index.md`, and `README.md`.
- Unit tests: 28 in `webagent4j-crawler-api` and 68 in `webagent4j-crawler` (`DefaultUrlNormalizer`
  - including an idempotence property test, `HostScopePolicy`, `BreadthFirstCrawlFrontier`,
  `InMemoryCrawlDeduplicator`, `HttpResponseClassifier`, `JsoupHtmlLinkExtractor`, and
  `HttpCrawlerTest` - the orchestrator itself, driven entirely through fake collaborators so no
  test touches the real network or sleeps in real time).
- Integration tests against a new deterministic local fixture (`HttpCrawlerTestServer`, a bare
  `com.sun.net.httpserver.HttpServer`, no browser): `HttpCrawlerIT` (HTTP-001..HTTP-020 - seed
  traversal, relative/root-relative/protocol-relative URL resolution, fragment and dot-segment
  dedup, same-host restriction, `maxDepth`/`maxPages` truncation, redirect chains, redirect loops,
  4xx/5xx handling with bounded retry, timeout, response-size limit, unsupported content type,
  Unicode content, `<base href>`, declared canonical URL, tracking-query dedup, mailto/javascript
  rejection, and malformed-markup tolerance) and `HttpCrawlerRobustnessIT` (CRAWL-001..CRAWL-010 -
  cyclic graphs, a hundred duplicate links, mixed dedup identities, external-host redirect
  rejection, thousands of duplicate links without frontier explosion, an unparsable href not
  crashing the page, a very large body rejected without full buffering, a redirect chain of
  exactly `maxRedirects` hops, an abrupt connection close, and an opaque backend exception
  classified as `BACKEND_FAILURE` - never silently becoming a fabricated `404`; plus the
  specification's mandated full end-to-end scenario, verified against every documented statistic).

### Fixed (HTTP Crawler — redirect identity, `maxPages`, attempts, and determinism)

- **A redirect chain could bypass `maxPages` entirely and re-fetch an already-fetched identity.**
  `fetchedUrls`/`maxPages` previously counted one unit per dequeued `CrawlTask`, never the redirect
  hops a task's chain actually requested over the network - so `maxPages=1` on `/a -> 302 /b -> 302
  /c` could still send 3 real requests, and two tasks whose redirects converged on the same final
  URL could fetch it twice. Introduced one central `claimFetchIdentity` gate in `HttpCrawler`,
  checked immediately before every real HTTP request (a task's own URL and every redirect hop
  alike): first claim proceeds, an identity already claimed by another task fails as the new
  `CrawlFailureType.ALREADY_FETCHED` (no page cache is kept to reuse - a structured signal instead
  of a silent re-fetch or a fabricated duplicate page), and a claim that would exceed `maxPages`
  fails as the new `CrawlFailureType.CRAWL_LIMIT_REACHED`, setting `terminationReason() ==
  MAX_PAGES_REACHED`. Every redirect target is normalized before this claim (and before loop
  detection), so a fragment or dot-segment disguise can no longer hide a duplicate or a loop.
  Discovery identity (`CrawlStatistics#discoveredUrls()`, seeds and links only) and fetch identity
  (`fetchedUrls()`, every real network target including redirect hops) are now two separate
  tracked sets rather than one conflated counter, replacing the previous (now false) `fetchedUrls
  == successfulPages + failedUrls` invariant this Javadoc used to claim. No general mathematical
  relationship is asserted between `discoveredUrls`, `fetchedUrls`, `successfulPages`, and
  `failedUrls` - each has its own precise, independent definition instead; see `CrawlStatistics`.
- **`CrawlFailure.attempts()` silently dropped the real retry count on a terminal HTTP status.**
  `RetryOutcome`'s success factory hardcoded `attempts = 0`, and every terminal-status branch in
  `HttpCrawler` hardcoded `attempts = 1` regardless of how many attempts were actually made - three
  `500` responses in a row reported `attempts = 1`, not `3`. Both call sites now thread the real
  attempt count through `RetryOutcome` and `FetchOutcome` end to end.
  `CrawlFailure.attempts()` also now allows `0` (relaxed from `>= 1`), the correct value for
  `CRAWL_LIMIT_REACHED`/`ALREADY_FETCHED`, decided before any request is ever sent.
- **`CrawlFailure` only ever reported the task's starting URL, never the URL that actually
  failed.** A failure two redirect hops deep (`A -> B -> C` failing on `C`) was reported as
  `url = A`, with no way to tell which hop actually failed. `CrawlFailure` now carries
  `requestedUrl` and `failedUrl` separately (equal only when no redirect was followed first) plus
  the full `redirectChain()` leading up to the failure.
- **`DiscoveredLink.normalizedUrl()` lied about links rejected before normalization was ever
  attempted** (an unsupported scheme, an out-of-scope host, or a URL `IUrlNormalizer` itself could
  not normalize) by placing the raw, un-normalized `resolvedUrl` there instead. Changed to
  `Optional<URI>`, empty exactly when normalization was never attempted or failed; a new invariant
  requires an *allowed* link to always carry its normalized URL.
- **A response outside the 2xx/3xx-redirect/4xx range fell into `HTTP_SERVER_ERROR` by
  elimination**, so a `304 Not Modified` (this phase has no HTTP cache to make use of one) or an
  unexpected `1xx` was misreported as a server error. Added `CrawlFailureType.UNEXPECTED_HTTP_STATUS`
  for exactly this range, reserving `HTTP_SERVER_ERROR` for genuine `5xx` responses.
- **`CrawlRequest.allowedSchemes()` accepted schemes `JavaHttpFetcher` cannot fetch** (`ftp`,
  `file`, arbitrary custom schemes), contradicting the "fully validated at construction" contract.
  Now validated to always be a non-empty subset of `{http, https}`, rejected at construction like
  every other misconfiguration.
- **`fetchDuration`/`elapsed` were measured with `Instant.now()`**, a wall clock that can jump
  independently of elapsed time and made two runs of the identical scripted crawl structurally
  unequal. `HttpCrawler` now accepts an injected `IMonotonicClock` (reused directly from
  `webagent4j-wait`, defaulting to `IMonotonicClock.systemClock()` in production; a new
  five-argument constructor overload exposes the seam), and `JavaHttpFetcher` does the same for
  `HttpFetchResult#elapsed()`. A new determinism test runs an identical scripted crawl (two pages,
  a redirect, a retry, and a rejected duplicate) twice against a fixed clock and asserts full
  `CrawlResult` equality.
- Bumped jsoup `1.18.3` -> `1.23.1`, resolving a moderate Dependency Review advisory
  (GHSA-pmhh-3w7g-xqp8, a `Cleaner`/Safelist issue this crawler's `Jsoup.parse`-only usage never
  reaches, flagged regardless).
- 4 new integration scenarios in `HttpCrawlerRobustnessIT` (CRAWL-011..CRAWL-014, against the real
  local HTTP server): redirect destinations from different seeds dedup globally, `maxPages` cannot
  be bypassed by a redirect chain, a redirect loop disguised behind a fragment/dot-segment variant
  is still detected, and failure provenance survives two redirect hops intact. 13 new
  `HttpCrawlerTest` unit scenarios covering the same fixes with a fake fetcher, plus new
  `CrawlFailureTest` (5 tests) and 2 new `CrawlRequestTest`/2 new `DiscoveredLinkTest` cases.
- Updated Javadoc on `CrawlRequest`, `CrawlStatistics`, `CrawledPage`, `CrawlFailure`,
  `DiscoveredLink`, `CrawlDecisionType`, `HttpFetchResult`, and `JavaHttpFetcher` to match the
  corrected behavior exactly; updated `docs/http-crawler.md`.

### Fixed (HTTP Crawler — final pre-merge consolidation)

- **`CrawlStatistics`'s Javadoc still asserted `fetchedUrls >= successfulPages + failedUrls`.**
  False in general: `CrawlFailureType.CRAWL_LIMIT_REACHED`/`ALREADY_FETCHED` add a `failedUrls`
  entry without ever claiming a new fetch identity. Rewrote `discoveredUrls`, `fetchedUrls`,
  `successfulPages`, and `failedUrls` to each carry one independent, precise definition, with no
  mathematical relationship asserted between them. The same over-claim (`fetchedUrls() >=
  discoveredUrls()`) was also present in `docs/http-crawler.md`'s Deduplication section and in this
  CHANGELOG's own previous entry - also false: the proactive `maxPages` check at discovery time only
  compares against the budget claimed *so far*, so a sibling task's own fetch can exhaust the
  budget between a link's discovery and its later, reactive `CRAWL_LIMIT_REACHED`. Both corrected,
  and a new `HttpCrawlerTest` scenario demonstrates `discoveredUrls > fetchedUrls` concretely.
- **`CrawlStatistics#maxDepthReached()` counted a task's depth before knowing whether it would ever
  send a real request.** `HttpCrawler.Session#run()` updated `maxDepthReached` right after dequeuing
  a task, before `processTask` even ran - so a task that was immediately blocked by
  `CRAWL_LIMIT_REACHED` or `ALREADY_FETCHED` (`attempts == 0`, no network call ever made) still
  inflated `maxDepthReached`. Moved the update into `fetchWithRedirects`, immediately after a task's
  own initial fetch-identity claim actually succeeds - the first point a real request is guaranteed
  to be sent for that task. Redirect hops still never change a task's own depth.
- **`CrawlFailure` allowed `attempts == 0` for any failure type**, not just the two outcomes decided
  before any request is sent. Added a constructor invariant: `attempts == 0` if and only if `type`
  is `CRAWL_LIMIT_REACHED` or `ALREADY_FETCHED`; every other type now requires `attempts >= 1`,
  rejected at construction otherwise - so a genuine network/HTTP/backend failure can never silently
  carry `attempts == 0`.
- **`failFast` could turn `CRAWL_LIMIT_REACHED`/`ALREADY_FETCHED` into `FATAL_ERROR`.**
  `HttpCrawler.Session#recordFailure` unconditionally set `stopRequested = true` for any recorded
  failure when `failFast` was `true`, including these two ordinary, expected outcomes of the crawl's
  own graph (a user-requested page budget, or two discovery paths converging on the same URL) -
  never a backend problem. A `maxPages` limit hit under `failFast = true` could therefore report
  `FATAL_ERROR` instead of `MAX_PAGES_REACHED`. `recordFailure` now only sets `stopRequested` for a
  genuine fetch failure; `CRAWL_LIMIT_REACHED` still sets `terminationReason() ==
  MAX_PAGES_REACHED`, and an `ALREADY_FETCHED` outcome lets the crawl keep processing the rest of
  the frontier even under `failFast`.
- **The determinism claim ("same input... always produce the same `CrawlResult`") did not account
  for `CrawlFailure#cause()`.** A `Throwable` never compares equal across two independently
  constructed instances, so two structurally identical failing runs (same type, same URLs, same
  `attempts`, same message) are never `CrawlResult#equals`. Re-scoped the guarantee to
  **deterministic logical crawl behavior** (frontier order, normalization, discovery, dedup, scope,
  redirect, retry, and page/failure ordering, statistics, provenance, and termination reason) and
  explicitly excluded `Throwable` identity from it, in `HttpCrawler`'s Javadoc, `CrawlFailure`'s
  Javadoc, and `docs/http-crawler.md`. `cause()` itself is unchanged - still the real exception, not
  a summarized DTO - preserving fail-closed diagnostics. Added a test comparing two identically
  scripted failing runs field-by-field rather than via `CrawlResult#equals`.
- Replaced the fragile `"12 CrawlFailureTypes"` count in the PR description and CHANGELOG with a
  description of the taxonomy's shape, so a future addition to `CrawlFailureType` does not silently
  make either document wrong.
- New `HttpCrawlerTest` scenarios: 4 `maxDepthReached` cases (never fetched, actually fetched,
  redirect-hop-count-independent, `ALREADY_FETCHED`-before-any-request), 3 `failFast` non-fatal
  cases (`CRAWL_LIMIT_REACHED`, `ALREADY_FETCHED` with continuation, and a genuine
  `BACKEND_FAILURE` still reaching `FATAL_ERROR`), 2 `CrawlStatistics` invariant cases, 1
  discovered-vs-fetched divergence case, and 1 failing-run field-equality case. New `CrawlFailureTest`
  cases for the `attempts`/`type` invariant (`ALREADY_FETCHED` accepts `0`; `HTTP_SERVER_ERROR`,
  `NETWORK`, `BACKEND_FAILURE` reject `0`; `CRAWL_LIMIT_REACHED` rejects a nonzero value).

### Added (Extraction — Phase 0.5)

- New `webagent4j-extraction-api` module (backend-neutral, depends only on `webagent4j-locator-api`):
  `ExtractionRequest<T>` (immutable, copy-on-write, describing a `LocatorDefinition` source, a
  `TEXT`/`ATTRIBUTE`/`VALUE` read type, a mandatory `IValueConverter<T>` - `text()`/`attribute()`/
  `value()` all start with `identity()`, so an engine never has to fall back to an unchecked cast -
  and an optional validator), `ExtractionResult<T>` (converted value, never `null`; pre-conversion
  raw string; `ExtractionProvenance`), `ExtractionProvenance`, `IValueConverter<T>` (`identity`,
  `toInteger`, `toLong`, `toBigDecimal`, `toBoolean`, `toLocalDate`), `IExtractionValidator<T>`
  (`nonBlank`, `range`, `matches`, `predicate`), `ExtractedTable`/`ExtractedRow`, and the failure
  taxonomy (`AExtractionException`, `ExtractionAttributeMissingException`,
  `ExtractionConversionException`, `ExtractionValidationException`).
  `ExtractionRequest#convertAndValidate(String)` is the one shared raw-&gt;convert-&gt;validate
  pipeline step, reused by both `ExtractionEngine` and `IElement#extract` below; a converter that
  returns `null` is itself treated as a conversion failure.
- New `webagent4j-extraction` module: `ExtractionEngine`, the deterministic engine reusing the
  existing `ILocatorEngine`/`ILiveLocatorContext`/`WaitEngine` machinery rather than a second DOM
  resolution engine. `extract()` and `extractTable()` resolve their source to exactly one
  unambiguous candidate (`ILocatorEngine#locateSingle` - the same contract `single()` already has),
  so an ambiguous source or an ambiguous table always raises `AmbiguousLocatorException`, never
  silently resolving to whichever candidate ranks first; `extractList()` resolves every matching
  source (`ILocatorEngine#locateAll`) and returns them in DOM order
  (`LocatorCandidate#domOrder()`, explicitly sorted - not necessarily the engine's rank order),
  failing the whole request rather than silently dropping a bad entry. `extractTable()` reads its
  `thead`/`tbody`/`tr`/`th`/`td` structure via `element.find().css(...)` with every selector
  anchored to direct children at each level (a `> thead > tr > th` chain, not a bare descendant
  selector), so a table nested inside one of this table's own cells never contributes its own
  headers, rows, or cells to this table's result.
- `ILocatorEngine` gains `locateAllWithScopePath` (default method, overridden by `LocatorEngine`):
  returns the same candidates as `locateAll` together with the scope path actually live-resolved for
  that search, so `extractList`'s provenance reports the real resolved scope (for example a frame's
  own `Frame[name="checkout"]` scope) rather than the caller's starting baseline scope - without a
  second, independent live resolution.
- `IPage`/`IFrame` gain `extract(ExtractionRequest<T>)`, `extractList(ExtractionRequest<T>)`, and
  `extractTable(LocatorDefinition)` as `default` methods that report "extraction is not supported by
  this backend" unless a backend overrides them (the Playwright adapter does) - keeping every
  existing `IPage`/`IFrame` implementation source-compatible. Frame-scoped extraction re-resolves the
  frame's own pending-scope chain fresh on every poll exactly like `IFrame#locate` already does, so a
  frame that disappears, is replaced, or becomes ambiguous mid-wait is caught the same way. A
  not-found or ambiguous source still raises the normal
  `LocatorNotFoundException`/`AmbiguousLocatorException`, never reinterpreted; a genuine
  backend/runtime failure always propagates unchanged.
- `IElement` (in `webagent4j-dom`, which now also depends on `webagent4j-extraction-api`) gains
  `extract(ExtractionRequest<T>)`: reads, converts, and validates directly from an already-resolved
  element, no locator search at all. Its provenance's `scopePath()` is always empty, since no
  locator scope is resolved to reach an already-resolved element. The one-directional
  `dom -> extraction-api` edge (never the reverse) is enforced by a new ArchUnit rule.
- 4 new example programs in `webagent4j-examples`: `ExtractTextExample`, `ExtractAttributeExample`
  (also demonstrates `extractList`), `ExtractTableExample`, `ExtractFromFrameExample` (typed
  conversion inside a frame).
- New `docs/extraction.md`; updated `docs/roadmap.md`, `docs/modules.md`, `docs/architecture.md`,
  `docs/limitations.md`, and `docs/index.md`.
- 3 new ArchUnit rules (`ArchitectureTest`): extraction stays independent from Playwright,
  `webagent4j-extraction-api` stays independent from the locator engine module (only
  `locator-api`), and `webagent4j-extraction-api` stays independent from `webagent4j-dom` (the
  `dom -> extraction-api` dependency direction is one-way only).
- Unit tests: 31 in `webagent4j-extraction-api` (converters, validators, request pipeline
  invariants including `convertAndValidate`'s null-converter-is-a-conversion-failure rule, table
  cell access, and a dedicated `ExtractionResultTest` proving a successful result can never carry a
  `null` value) and 20 in `webagent4j-extraction` (`ExtractionEngineTest`, covering text/attribute/
  value reads, missing-attribute vs missing-element, conversion-then-validation ordering, NOT_FOUND/
  AMBIGUOUS/backend-failure propagation, two dedicated regression tests proving `extract`/
  `extractTable` resolve through `locateSingle` rather than `locate` - which would fail if either
  were ever swapped back - DOM-order list ordering against a fake engine whose rank order and DOM
  order deliberately disagree, list provenance using the live-resolved scope path rather than the
  baseline, and table header/row reading including the no-`thead` case). Plus 1 new test in
  `webagent4j-locator` (`LocatorEngineTest`) covering `locateAllWithScopePath` directly.
- Real-browser integration tests: `ExtractionIT` (EXT-001..EXT-015: simple/Unicode text, attribute,
  live form value, list, table, not-found, ambiguous source, single/nested iframe, frame
  replacement, ambiguous table, nested-table isolation, and frame/nested-frame list provenance) and
  `ExtractionRobustnessIT` (cross-scope leak, sibling-frame leak, stale-element replacement, empty
  table, ragged table row, missing attribute on an existing element, unparsable-number conversion
  failure) against `ExtractionTestApplication`. Backend-failure propagation during extraction is
  covered at the unit level (`ExtractionEngineTest`) rather than as a real-browser IT, since
  reliably forcing a genuine backend disconnect mid-extraction without flakiness isn't reasonably
  achievable in this suite.

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

### Fixed (fail-closed candidate-identity inspection)

- **A genuine backend or runtime failure encountered while evaluating a discovered candidate's
  identity is no longer silently absorbed as "this candidate vanished".** `PlaywrightLocatorBackend`'s
  `identifyOrNull(Locator)` - introduced to make a candidate that vanishes between `Locator#count()`
  and this call fail fast instead of blocking on Playwright's multi-second default actionability
  wait - caught every `RuntimeException` alike and returned `null` for all of them, treating a
  disconnected browser, a closed context/page, or any other opaque backend failure exactly the same
  as an ordinary detachment race. It now catches only Playwright's typed `TimeoutError` - its actual
  signal for "did not resolve within the bounded inspection timeout" - and lets every other
  `RuntimeException` propagate unchanged, matching the same fail-closed idiom already used elsewhere
  in this class (`matchesUrl`'s candidate-vanishing check) and this codebase generally.
- 2 new unit tests in `PlaywrightLocatorBackendTest` (new file): a candidate whose identity
  evaluation raises `TimeoutError` is excluded from that poll with no exception propagated, and a
  genuine backend failure (`IllegalStateException`) during the same call propagates as the exact
  same instance rather than becoming an empty result. `ILocator#tryFind()`'s existing
  backend-failure-propagation tests (`ILocatorTryFindTest`) already cover, generically, that any
  such failure reaching a terminal locator operation is never reported as "not found" - this closes
  the gap at the one place upstream of that contract that could previously have masked it.

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

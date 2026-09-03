# Changelog

All notable changes to WebAgent4J are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Semantic Versioning
compatibility commitments begin with `1.0.0`; pre-1.0 milestones describe development history and do
not imply a published compatibility line.

## [Unreleased]

### Added

- Deterministic Workflow Branching: `WorkflowSteps.ifElse(id, condition, thenSteps, elseSteps)` and
  `WorkflowSteps.ifThen(id, condition, thenSteps)` add a deterministic `if`/`else` control-flow step
  to `webagent4j-workflow` - a condition evaluated exactly once, selecting exactly one of two step
  sequences, nested conditional branching supported up to a 64-level limit (each branch measured
  independently, never summed), never both, never a retry, and never a fallback from a failed
  branch to the other one. A definition nested deeper than that limit is rejected at build time
  with a controlled error, never a `StackOverflowError`. The branch not selected produces zero step
  executions, zero action factory calls, and zero backend invocations. A failed condition
  evaluation fails the conditional step closed (`CONDITION_EVALUATION_FAILED`) rather than being
  treated as `false`; an interrupt observed at either of the step's two structural boundaries fails
  it closed with the new `CONDITIONAL_STEP_INTERRUPTED` failure type. `WorkflowResult.steps()`
  stays one flat, execution-ordered list - the conditional step's own decision immediately followed
  by whichever single branch actually ran - so Recording V1 captures a branching execution with no
  format change. See [Workflows](docs/workflow.md#branching) and
  [Limitations](docs/limitations.md#workflows).

- Governed Actions V2: extended atomic exact-target execution to every target-bound governed
  action, not just `click` - `type`/`fill`, `select`, `check`, `uncheck`, `hover`, and `pressKey`
  now share the same `IElement#verifiedForExecution()` atomic-handle binding `click` already had,
  proven with new real-browser adversarial tests (physical replacement during policy evaluation,
  and after the exact handle is already bound, both fail closed with zero backend invocations) and
  a generic-pipeline test matrix covering all eight actions' policy-authorization, deadline, and
  interruption boundaries. Also adds `typeSequentially`/`typeSequentiallySecret`
  (`ActionType.TYPE_SEQUENCE`), a genuinely new action distinct from `type`/`fill`: it dispatches
  one keyboard/input event per character rather than replacing the value directly, so the result
  depends on the target's current value/selection/caret and any application JavaScript handling
  those events - unlike `type`/`fill`, it is `NON_IDEMPOTENT`. Shares the identical
  governed-execution pipeline and exact-target guarantee. See
  [Governed execution](docs/governed-execution.md#target-identity-binding) and
  [Limitations](docs/limitations.md#governed-execution).

- Governed execution: `IActionPolicy` authorizes an action before its backend side effect runs, and
  `INetworkPolicy` authorizes a `NAVIGATE` action's or crawler's network destination before a
  request is sent - both opt-in, built on a shared synchronous, fail-closed `IExecutionPolicy`
  contract with composition (`ExecutionPolicies.allOf`, `ActionPolicies`) and decision provenance
  (`ActionResult#decisionTrace()`, `IActionPlan#policyDecisions()`). Default behavior is unchanged
  unless a caller configures a policy. See [docs/governed-execution.md](docs/governed-execution.md).
- `NetworkPolicies`, a declarative network-destination policy builder covering scheme/host/port
  allow-lists and IPv4/IPv6 special-use address category denial (loopback, private, link-local,
  multicast, shared, documentation, benchmark, reserved), with an injectable
  `INetworkAddressResolver` seam for deterministic testing.
- `HttpCrawler#withNetworkPolicy(...)` and `BrowserCrawler#withNetworkPolicy(...)`, checking every
  real request/navigation against the configured policy before it is sent, including every redirect
  hop and retry attempt, with interruption preserved throughout.
- Exact-target execution protection: `IElement#verifiedForExecution()` atomically reproves a governed
  action's already-resolved target immediately before its backend call and hands back a view bound
  to that exact physical handle, failing closed (`ActionFailureType.TARGET_CHANGED`, zero backend
  invocations) rather than falling back to a re-resolved element when identity cannot be reproven.
  Currently wired for the Playwright adapter's `click()`; see
  [docs/governed-execution.md](docs/governed-execution.md#target-identity-binding) and
  [Limitations](docs/limitations.md#governed-execution) for its current per-action-method scope.
- Transport-bound address pinning: when a configured `INetworkPolicy` implements
  `INetworkAddressAuthority` (the built-in `NetworkPolicies` policy does), `HttpCrawler` binds its
  actual HTTP(S) connection to the exact, freshly re-verified address set the policy offers for that
  specific attempt, closing the DNS-rebinding window between policy check and physical connect for
  that controlled transport path - denying the request rather than silently falling back to an
  unpinned connection once transmission may have started. See
  [docs/governed-execution.md](docs/governed-execution.md#transport-bound-address-pinning-httpcrawler-only).

### Changed

- The complete deterministic adversarial robustness corpus now passes, with zero wrong targets, for
  Chromium, Firefox, and WebKit together on the same code state, and the release workflow gates every
  engine equally before publication. See
  [docs/support-matrix.md](docs/support-matrix.md#browser-and-robustness-qualification-by-operating-system)
  for the exact current scope of that evidence.

### Fixed

- Corrected candidate-identity tracking in the Playwright frame/document trust bridge: a browser
  engine that can replace a document while keeping its owning execution realm alive no longer causes
  a still-current, still-attached physical node to be rejected as a stale document mismatch.
- `JsonWorkflowRecordingCodec#encode` now enforces the same step-count, string-length, and
  total-document-size limits `decode` enforces, so `decode(encode(recording))` no longer fails on
  this codec's own resource bounds: a recording too large for this codec to decode back is now
  rejected by `encode` itself instead of being silently accepted and handed to a caller as JSON this
  codec cannot read back. See
  [docs/recording.md](docs/recording.md#decoding-resource-bounds).

### Security

- Documented that governed execution's network policy is not a general SSRF firewall, and that a
  configured policy is untrusted, unsandboxed Java code with the same trust posture as a plugin.
  Documented precisely, rather than as a blanket claim, where DNS-rebinding protection does and does
  not apply: `HttpCrawler` closes the check-to-connect window only when the configured policy exposes
  `INetworkAddressAuthority`; a fully custom policy that does not implement it gets no pinning, and
  browser navigation has no equivalent transport-level seam at all.
- Documented that a governed `NAVIGATE` action or `BrowserCrawler` visit can only detect, never
  prevent, a browser-internal redirect landing somewhere a network policy would have denied, unlike
  `HttpCrawler`, which controls its own redirect loop.
- `JsonWorkflowRecordingCodec#decode` now enforces deterministic, framework-owned resource limits
  (overall document size, JSON nesting depth, string/field-name/numeric-token length, and step
  count) before the allocation each one protects, so a caller-supplied recording can no longer force
  unbounded parsing, tree construction, or collection allocation before being rejected. See
  [docs/recording.md](docs/recording.md#decoding-resource-bounds).
- A built-in workflow condition (`WorkflowConditions#equals`/`notEquals`, and any `not`/`allOf`/
  `anyOf` composed only from them) no longer renders its comparison literal eagerly at step-evaluation
  time and retains the (potentially unbounded) rendered text for the rest of the execution; rendering
  is now deferred to workflow finalization, when the complete secret set is already known, so the
  text is created, redacted, and bounded in one step instead. The mandatory `render → redact → bound`
  ordering, and behavior for a custom `IWorkflowCondition`, are unchanged. See
  [docs/workflow.md](docs/workflow.md#resource-bounded-diagnostics).
- `HostScopePolicy` now rejects a candidate URL longer than a documented maximum before evaluating
  it against any configured `includeUrlPattern`/`excludeUrlPattern`, bounding the worst-case input
  size a caller-supplied regex is ever evaluated against. Documented precisely, rather than as a
  blanket claim, what this bound does and does not establish: it is a genuine, deterministic cap on
  attacker-controlled input length, not a guarantee that every possible pattern is safe to evaluate
  even within that bound, since Java's backtracking regex engine has no reliable way to cancel a
  match already in progress. The exclude-pattern-match rejection diagnostic no longer echoes the
  caller's own pattern text. See
  [docs/security-model.md](docs/security-model.md#url-filter-pattern-safety).

## [1.0.0] - 2026-08-27

### Changed

- Restructured the documentation around one authoritative source per contract instead of repeating
  release state, security rules, support promises, timeout semantics, and limitations across
  unrelated guides.
- Added explicit release-readiness, documentation-governance, security-model, support-matrix, CLI,
  and Recording V1 schema references.
- Clarified the distinction between implemented browser backends and release-qualified support.
- Clarified that stable releases require immutable version-specific Javadoc/documentation; the
  `api/latest` site represents the current development line.
- Release-readiness work is intentionally feature-frozen: packaging, metadata, publication,
  documentation, CI/release automation, and artifact verification may change without adding a new
  product capability.

### Security

- Documented the network trust boundary explicitly: crawler host/scheme policies are not a universal
  SSRF defense, `robots.txt` is not automatically enforced, and applications accepting untrusted
  destinations must apply their own allowlist or equivalent policy.
- Documented plugins as trusted, unsandboxed, in-process Java code.
- Limited safe-logging guarantees to representations whose contracts explicitly define them as safe;
  arbitrary values, exceptions, URIs, metadata, and `toString()` output remain application review
  boundaries.
- Clarified the Recording V1 trust boundary: documented raw workflow/action value channels are
  excluded, while caller/action identifiers such as `RecordingId` and `ActionId` may be persisted
  verbatim.

### Fixed

- Hardened numeric and timing invariants: retry configuration rejects non-finite factors, duration
  arithmetic saturates instead of overflowing, unit-interval values reject `NaN`, and elapsed
  deadline decisions use monotonic time.
- Preserved caller interruption and the exact action status/execution-mode/failure-type matrix across
  action, workflow, and Recording V1 projections.
- Prevented Playwright inspection time from creating independent comfort windows larger than the
  caller's remaining budget.
- Tightened Playwright absence classification: disappearance is reported only after supported fresh
  evidence proves absence; opaque backend/runtime failures continue to propagate fail-closed.
- Hardened structured Playwright scopes against DOM reorder, physical replacement, late ambiguity,
  application-controlled identity state, and repeated re-resolution:
  - semantic cardinality is classified before physical identity is consulted;
  - one stable physical identity is associated with a live DOM node;
  - transient binding state is constant-space rather than an unbounded history;
  - later resolutions cannot arbitrarily expire an older still-valid live scope;
  - physical replacement cannot silently inherit the old scope identity;
  - trusted DOM containment uses primitives captured before application JavaScript can monkey-patch
    them.
- Corrected Playwright adoption/document-context race classification without converting opaque
  failures into fabricated absence.
- Closed resource-ownership gaps in Playwright scope validation, including disposal of temporary
  element handles.
- Added deterministic regressions for the corrected hostile timing, identity, ambiguity, resource,
  interruption, schema, plugin, workflow, crawler, and recording boundaries.
- Recording JSON schema V1 and the stabilized public Java/SPI surface remain unchanged by the
  adversarial-hardening corrections.

For the maintained hardening evidence and final invariants, see
[`docs/hardening.md`](docs/hardening.md). The implementation history of intermediate fixes is kept in
Git rather than duplicated here.

## Pre-1.0 development milestones

The milestones below summarize the capabilities and compatibility work that led to the 1.0
candidate. They are historical summaries, not separate supported release lines.

### 1.0-C — Robustness and adversarial hardening

Validated the stabilized contracts under hostile timing, lifecycle, DOM mutation, backend failure,
schema, extension, interruption, and resource-ownership conditions. The phase added no product
feature, public API/SPI surface, Maven coordinate, runtime dependency, or Recording V1 schema field.

Final hardening guarantees include fail-closed Playwright candidate/scope identity, constant-space
scope identity state, trusted containment, bounded adapter work, monotonic timing, interruption
preservation, no hidden side-effect retry, strict Recording V1 decoding, and secret-safe framework
diagnostics where explicitly documented.

See [`docs/hardening.md`](docs/hardening.md).

### 1.0-B — Cross-module contract consistency

Aligned shared invariants without introducing a universal result abstraction:

- exact action status/execution-mode/failure-type matrices;
- workflow and recording projection consistency;
- at-least-one-step workflow/recording traces;
- monotonic elapsed timing and non-negative public durations;
- interruption semantics before and after possible side effects;
- resource ownership and thread-safety claims;
- backend failure versus demonstrated absence;
- safe diagnostic rendering boundaries.

Recording schema V1 remained unchanged.

See [`docs/contracts.md`](docs/contracts.md).

### 1.0-A — Public API stabilization

Inventoried and classified the intended 1.0 Java, SPI, Maven, runtime-public, CLI, and
implementation-public surfaces.

Key cleanup included:

- narrowing accidental concrete return types;
- moving exception construction behind authoritative loaders where appropriate;
- explicitly rejecting misleading native Java serialization for structured exceptions that could
  lose required state;
- strengthening required-value, finite-number, diagnostics, and backend-failure contracts;
- removing empty placeholder modules from supported BOM commitments;
- defining Semantic Versioning behavior for the post-1.0 supported surface.

See [`docs/api-stability.md`](docs/api-stability.md) and
[`docs/migration-to-1.0.md`](docs/migration-to-1.0.md).

### 0.9-B — Trusted locator plugins

Added explicit `ServiceLoader` discovery for trusted custom locator strategies through
`webagent4j-plugin-api`.

Discovery is opt-in, deterministic, immutable, and fail-closed. The default locator/browser path
loads zero plugins. Duplicate plugin/strategy IDs and invalid contributions are rejected. Plugins
cannot override built-in strategies and are not sandboxed.

See [`docs/plugins.md`](docs/plugins.md).

### 0.9-A — Recording foundation

Added `webagent4j-recording` with:

- immutable schema-versioned recordings;
- strict deterministic JSON encoding/decoding;
- Recording schema V1;
- exclusion of documented raw workflow/action value channels;
- explicit verbatim metadata trust boundaries;
- pure offline comparison against a caller-supplied new `WorkflowResult`.

A recording is data, not a browser program. No automatic live replay is implemented.

See [`docs/recording.md`](docs/recording.md).

### 0.8 — Workflows

Added deterministic, sequential, fail-fast workflow orchestration over the action pipeline:

- immutable workflow definitions;
- typed required/optional inputs;
- write-once variables;
- masked secret variables;
- guarded sequential steps;
- structured full execution traces;
- no hidden workflow retry, parallel branches, persistence, or general programming-language DSL.

See [`docs/workflow.md`](docs/workflow.md).

### 0.7 — Browser crawler

Added a deterministic single-lane browser crawler for JavaScript-rendered pages, using a
caller-supplied browser session, bounded navigation/stability behavior, cancellation, scope checks,
and structured partial results.

See [`docs/browser-crawler.md`](docs/browser-crawler.md).

### 0.6 — HTTP crawler

Added deterministic sequential HTTP crawling with normalized URL identity, BFS frontier traversal,
deduplication, host/domain policies, bounded response handling, redirects, explicit retry policy,
structured failures, and deterministic result ordering.

See [`docs/http-crawler.md`](docs/http-crawler.md).

### 0.5 — Extraction

Added deterministic text, attribute, form-value, list, and HTML-table extraction using the existing
locator engine, with typed conversion, validation, provenance, and structured extraction failures.

See [`docs/extraction.md`](docs/extraction.md).

### 0.4 — Actions and verification

Added planning, dry-run and real execution, preconditions, stabilization, verified postconditions,
structured action results, and exactly-once backend side-effect boundaries.

See [`docs/actions.md`](docs/actions.md) and [`docs/verification.md`](docs/verification.md).

### 0.3 — Semantic observation

Added immutable bounded semantic page observations, redaction, compact/JSON rendering, fingerprints,
diffs, semantic relationships, and observation statistics.

See [`docs/observation.md`](docs/observation.md).

### 0.2 — Semantic locators

Expanded deterministic semantic location with roles, accessible names, structured contexts, frames,
shadow-DOM-aware supported selectors, ranking, ambiguity detection, and explainable diagnostics.

See [`docs/locators.md`](docs/locators.md).

### 0.1 — Browser foundation

Established Java 21, the Maven multi-module architecture, backend-neutral browser contracts, the
Playwright provider, CLI/examples, CI, deterministic semantic foundations, and the Apache-2.0
open-source project baseline.

## Compatibility and schema notes

- Semantic Versioning compatibility commitments begin with `1.0.0`.
- Recording JSON compatibility is versioned independently through its explicit `schemaVersion`.
- Native Java serialization is not a persistence or compatibility format for WebAgent4J public
  values.
- Internal implementation packages and runtime-public implementation details are governed by
  [`docs/api-stability.md`](docs/api-stability.md), not by Java's `public` modifier alone.

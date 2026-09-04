# Roadmap

The roadmap is non-normative. It describes direction, not a compatibility promise or delivery date. Current behavior is defined by Javadoc and the guides linked from [index.md](index.md).

## Completed foundations

The pre-1.0 program established:

- browser lifecycle and Playwright adapter;
- deterministic semantic locators/scopes/frames;
- bounded semantic observation;
- verified actions and shared verification/wait primitives;
- deterministic extraction;
- sequential HTTP and browser crawlers;
- sequential fail-fast workflows with typed variables/secrets;
- workflow Recording JSON V1 and offline comparison;
- explicit trusted custom locator plugins;
- public API stabilization/cross-module invariant alignment;
- adversarial numeric/timing/backend/identity/resource hardening.

Historical phase labels are retained only as release-development history; user guides no longer require knowledge of those phases.

## 1.0 release engineering

`1.0.0` is published. Its release engineering covered release engineering/documentation/publication rather than a new product feature:

- final API/SPI/artifact/CLI support review;
- current security policy alignment;
- immutable versioned Javadoc/documentation;
- shaded-artifact license/notice review;
- exact-head CI/CodeQL/Dependency Review/robustness certification;
- migration/release notes and post-publication smoke verification.

Maven/publication metadata/signing/repository configuration for public Maven distribution (for example, Maven Central) remains a separate, not-yet-selected item.

See [release.md](release.md) for the runbook used for this and future releases.

## 1.1: Governed execution

`1.1.0` is released and is part of the current stable `1.1.x` line. It added opt-in governed
execution: `IActionPolicy` authorizes an action before its backend side effect runs, and
`INetworkPolicy` authorizes a `NAVIGATE` action's or a crawler's network destination before a
request is sent, both built on a shared synchronous `IExecutionPolicy` contract with composition
(`ExecutionPolicies.allOf`) and decision provenance (`ActionResult#decisionTrace()`). Default
behavior is unchanged; nothing is governed unless a caller configures a policy. See
[Governed execution](governed-execution.md).

This is not a general SSRF firewall, a policy sandbox, or a remote/LLM-assisted authorization layer.
It is also not a blanket DNS-rebinding defense: `HttpCrawler` closes the check-to-connect window only
for the specific case where the configured network policy exposes `INetworkAddressAuthority` (the
built-in declarative policy does; a fully custom policy does not unless it implements that capability
too), and browser navigation has no equivalent transport-level seam at all - see that document's
"What this is not" section for the precise scope.

## 1.2: Deterministic branching, structured workflow explainability, and Governed Actions V2

`1.2.0` is released and is the current stable line (`1.2.x`). It added:

- **Governed Actions V2** - the atomic exact-target execution guarantee `1.1.x` wired for `click`
  alone now covers every target-bound governed action (`type`/`fill`, `select`, `check`, `uncheck`,
  `hover`, `pressKey`), plus a new `typeSequentially`/`typeSequentiallySecret` action. See
  [Governed execution](governed-execution.md#target-identity-binding).
- **Deterministic workflow branching** - `WorkflowSteps.ifElse`/`ifThen` add a single evaluate-once
  conditional step with guard-aware, path-sensitive definite assignment for branch outputs. See
  [Workflows](workflow.md#branching).
- **Structured Workflow Execution Tree** - `WorkflowEngine#executeWithTree` returns a hierarchical
  view of the control-flow path one execution actually took, alongside the existing flat
  `WorkflowResult`. See [Workflows](workflow.md#execution-tree).
- **Deterministic Workflow Execution Plan** - `WorkflowPlanner.plan` returns a static, backend-neutral
  description of what a workflow definition is structurally capable of executing, built without
  running it. See [Workflows](workflow.md#execution-plan).
- **Structured Workflow Validation Report** - `Workflow.Builder#validate()` explains a definition's
  current validity from the same internal analysis `build()` already uses, without throwing or
  mutating the builder. See [Workflows](workflow.md#validation-report).

See [`CHANGELOG.md`](../CHANGELOG.md) for the complete description of this release.
`1.1.x` (final release: `1.1.1`) is the previous stable line.

## 1.3: active development

`develop` is `1.3.0-SNAPSHOT`, the active line for the next release. `1.2.0` remains the current
stable line (`1.2.x`) until a future release supersedes it. In progress:

- **Recording V2 and Deterministic Replay** - `WorkflowRecordingV2` captures a tree-shaped
  workflow execution (a `WorkflowExecutionPlan` plus a tree mirroring `WorkflowExecutionTree`) with
  typed, secret-classified published outputs, replacing V1's flat step list and bare output-variable
  name; a new `io.webagent4j.recording.replay` package validates a recording's compatibility with a
  live workflow and deterministically replays its recorded decision path. This initial
  implementation is structural/decision replay only - it never evaluates a condition, never invokes
  an action factory, never touches a backend, and never performs any side effect; a `FAILED`
  recording and real governed-target side-effect replay are explicitly out of scope for now. See
  [Recording](recording.md#recording-v2).

## Post-1.2 candidates

Full SSRF isolation, `robots.txt` support, adversarial robustness qualification for Firefox and
WebKit on operating systems beyond Linux, distributed crawling, additional observation/extraction
capabilities, explicit persistence, and optional external decision-system/MCP adapters remain
candidates.

These are candidates only. None is implied by the 1.0 API contract or by the `1.2.0` release, none
is a commitment to a `1.3.0` scope, and any optional decision/AI layer must consume the same
fail-closed public contracts rather than bypassing them. Real governed-target side-effect replay
(actually re-invoking a recorded action against a freshly re-verified target), workflow loops, and
workflow parallelism remain undecided and are not committed by this roadmap - see
[Recording](recording.md#deterministic-replay) for the side-effect-replay scope decision already
made for 1.3.

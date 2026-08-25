# Migration to the 1.0 API

This guide records pre-1.0 changes that callers may need to address when moving from older development snapshots to the 1.0 contract. It is historical migration guidance; current semantics live in the domain guides.

## Plugin registry composition

`PluginRegistry#locatorStrategyRegistry()` is consumed through `ILocatorStrategyRegistry` rather than requiring the concrete registry type.

```java
ILocatorStrategyRegistry strategies = plugins.locatorStrategyRegistry();
LocatorEngine locator = new LocatorEngine(strategies);
```

Avoid casting back to implementation classes.

## Plugin exceptions

`PluginLoadException` construction is loader-owned. Applications catch it and inspect structured `failure()` rather than manufacturing loader failures. Native Java serialization of structured exceptions is not a persistence contract.

## Required values and invariants

Previously under-specified invalid values now fail immediately: required null/blank fields, negative elapsed durations, non-finite unit-interval values, non-positive configured request timeouts, and impossible workflow/action/recording combinations.

Fix invalid fixtures/application DTO construction rather than relying on contradictory objects surviving until a later engine call.

## Safe diagnostics

Several exception/result renderings were narrowed so raw extracted values, URIs, causes, target/result metadata, or arbitrary provider messages are not copied into framework-owned safe summaries.

Do not parse exception messages. Use typed fields in-process and apply an application-owned redaction policy before logging raw accessors/causes.

## Locator/backend failure classification

Backend timeout/runtime failure is no longer treated as generic absence. Known Playwright disappearance races require fresh proof of absence/frame unavailability; otherwise the original backend error propagates.

Code that previously interpreted backend failure as an empty/not-found result must handle the real failure.

## Action result matrix

Directly constructed action/workflow/recording projections must follow the exact status/execution/failure matrix documented in [contracts.md](contracts.md#action-outcome-matrix).

Interruption before backend invocation is `CANCELLED/NOT_EXECUTED/INTERRUPTED`; after invocation or possible side effect it is `CANCELLED/REAL/INTERRUPTED`. Planning interruption is a BLOCKED plan with `INTERRUPTED`.

## Workflow/recording trace shape

Workflow results and recordings require at least one step. Completed/preflight/runtime-failure traces must match reachable fail-fast execution. Strict recording decode rejects impossible historical fixtures rather than normalizing them.

Recording schema version remains V1; these are invariant corrections, not a field/version migration.

## Timing

Elapsed action/locator/wait timing uses monotonic clocks; wall-clock time is limited to absolute timestamp fields. Timeout arithmetic is overflow/rollover-safe and adapter sub-operations consume the caller's bounded remaining work.

## Structured scopes

Structured semantic scopes now preserve fail-closed ambiguity and physical identity through dynamic DOM reordering/replacement races. Code that depended on DOM index, page-controlled identity markers, or silent substitution was never a supported contract and must use semantic references/scopes instead.

## BOM/placeholders

Reserved empty `webagent4j-http`, `webagent4j-storage`, and `webagent4j-testing` are not supported BOM-managed application dependencies. Remove application dependencies on them.

## What did not change

- no automatic live replay was added to Recording;
- plugin discovery remains explicit/trusted and zero-plugin by default;
- workflow remains sequential/fail-fast with no hidden workflow retry;
- no AI/OCR/MCP dependency was introduced into the core;
- Playwright remains behind backend-neutral public contracts.

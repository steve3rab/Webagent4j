# Migration to the 1.0 API candidate

Phase 1.0-A intentionally performs a small set of pre-1.0 cleanups before the compatibility surface
is frozen. No recording schema or product feature changes in this phase.

## Plugin registry composition

Before:

```java
LocatorStrategyRegistry strategies = plugins.locatorStrategyRegistry();
```

After:

```java
ILocatorStrategyRegistry strategies = plugins.locatorStrategyRegistry();
LocatorEngine locator = new LocatorEngine(strategies);
```

Migration: type variables and parameters to `ILocatorStrategyRegistry`. The interface already
contains the complete composition contract required by `LocatorEngine`; do not cast the result back
to the concrete implementation.

Reason: `PluginRegistry` should not expose a locator implementation class when the supported
interface is sufficient. This is source compatible for direct method-call composition, but source
incompatible for callers assigning to the concrete type and binary incompatible for already
compiled callers.

## Plugin load exception construction and serialization

Before, applications could invoke the public `PluginLoadException(PluginLoadFailure)` constructor.
After, construction is owned by `PluginLoader`; applications catch `PluginLoadException` and inspect
`failure()`.

Migration: construct or return the structured `PluginLoadFailure` in application-owned validation
code instead of manufacturing a loader exception. Only `PluginLoader` defines when a plugin load
has failed.

Native Java serialization of `PluginLoadException` is now explicitly unsupported and throws
`NotSerializableException`. The same rule is explicit for other structured exceptions whose typed
state would otherwise be lost. Migration: do not persist exceptions with
`ObjectOutputStream`; persist an application-defined safe error DTO when necessary.

Reason: deserialization must never create an unusable exception whose required `failure()` is null.

## Required arguments and value invariants

The following previously under-specified invalid inputs now fail immediately:

- required base-exception messages and required structured failure/result arguments cannot be null;
- public locator diagnostic overloads require a non-null diagnostics object; use the one-argument
  constructor when diagnostics are intentionally absent;
- extraction conversion exceptions require their raw value and target type; validation exceptions
  require a non-blank description and may retain an explicitly rejected null value;
- `ResponseTooLargeException` requires a non-null URI and a positive limit;
- `BoundingBox` requires finite coordinates and non-negative finite dimensions.

Migration: validate input at the application boundary and use the explicit overload for absent
locator diagnostics. Valid existing calls are unchanged.

Reason: public values and exceptions must not carry locally invalid required state into later code.

## Safe diagnostic text

Extraction exceptions no longer include a raw extracted value or arbitrary cause message in
`getMessage()`. `ResponseTooLargeException` no longer includes the URI in its message.
`CrawlFailure#toString()` and `BrowserCrawlFailure#toString()` now describe structural fields without
rendering the URL, message, or raw cause.

Migration: use typed accessors for in-process handling. Treat values, URIs, and retained causes as
sensitive and apply an application-owned redaction policy before logging or persistence. Do not
parse exception messages.

Reason: framework-owned diagnostics should not accidentally disclose untrusted or sensitive input.

## Playwright inspection failure classification

A Playwright inspection timeout is translated to expected element disappearance only when a fresh
count confirms the element is gone. If the element remains present, or the backend recheck fails,
the original backend exception propagates. The canonical Playwright protocol error whose first
field is `Frame was detached` is also definitive disappearance because Playwright 1.60 provides no
dedicated Java subtype for it; incidental text in any other error is not classified as absence.

Migration: code that incorrectly depended on backend failures appearing as absent metadata must
handle the genuine backend failure. Typed disappearance behavior is unchanged.

Reason: fail-closed behavior must distinguish expected absence from a broken browser/backend.

## BOM alignment

The BOM no longer manages the empty placeholder artifacts `webagent4j-http`,
`webagent4j-storage`, or `webagent4j-testing`.

Migration: remove dependencies on those empty artifacts. They provide no supported API. Reactor
modules remain in the source tree as explicitly unsupported boundaries.

Reason: dependency management should describe consumable artifacts rather than create an implied
compatibility promise for placeholders.

## Unchanged contracts

- Recording JSON remains schema V1; no field, ordering, decoding, or replay behavior changes.
- Plugin discovery remains explicit, deterministic, trusted, and zero-plugin by default.
- Workflow execution remains sequential and fail-fast with no hidden workflow retry.
- No new locator strategy, workflow step, crawler behavior, persistence, AI, agent, or MCP feature
  is introduced.

## Phase 1.0-B behavioral contract corrections

Phase 1.0-B changes no public Java signature and does not change recording schema V1. It rejects
public values that could not be produced or consumed consistently:

- direct `ActionResult`, `WorkflowActionSummary`, `RecordedAction`, `WorkflowFailure`,
  `WorkflowStepResult`, and `WorkflowResult` construction must follow the engines' documented
  status/execution and sequential fail-fast shapes;
- elapsed durations cannot be negative, configured `HttpFetchRequest` timeouts must be positive,
  successful waits carry a value, timed-out waits carry no achieved-stability duration, and a
  successful verification cannot also be timed out;
- `ActionEvent#toString()` no longer includes target, result, or metadata text;
- Playwright inspection timeout is absence only after a fresh count proves disappearance; the
  canonical frame-detached protocol error is also a definitive disappearance signal.

Migration: fix invalid test fixtures or application-created DTOs at their construction boundary,
and use typed action-event accessors only inside an application-owned redaction policy. Valid
engine-produced values are unchanged. Code that depended on a still-present Playwright target being
reported absent must handle the original backend timeout instead.

# ADR 0008: Action and verification engine

## Context

Browser automation needs more than issuing native commands. Semantic targets may appear late,
preconditions may fail, page state may settle asynchronously, and a side effect may succeed before
its expected outcome becomes observable. Retrying the complete operation can duplicate submissions,
downloads, or purchases. Native exceptions alone cannot explain which lifecycle phase failed.

## Decision

WebAgent4J models actions as immutable commands executed by a common staged pipeline. Target
resolution, preconditions, backend execution, stabilization, observation, and verification remain
separate concerns. The browser adapter implements a backend-neutral action port; no public action or
verification contract exposes Playwright.

Backend execution for a non-idempotent command occurs at most once. Bounded retries apply to semantic
target resolution, while postcondition polling only observes state after execution. Before and after
state uses the existing bounded semantic observation model and produces an optional semantic diff.

Normal operational failures are returned as structured statuses, categories, timings, events, and
safe diagnostics. Exceptions remain appropriate for invalid API use and are available on demand
through `ActionResult.throwIfFailed()`.

## Alternatives

- Direct calls on `IElement` were rejected because they cannot enforce one lifecycle consistently.
- Retrying the complete action was rejected because idempotency cannot be assumed.
- Browser-native wait objects were rejected as public contracts because they couple applications to
  one adapter.
- Raw exceptions were rejected as the only failure channel because they obscure lifecycle stage and
  are difficult to audit safely.
- Full DOM capture was rejected in favor of the existing redacted semantic observation model.

## Consequences

Applications receive predictable verified outcomes and can choose result-oriented or
exception-oriented handling. Adapters must implement the action port and preserve timeout, path, and
secret-safety guarantees. Polling adds bounded latency, but never duplicates the original side
effect. Future backends and optional decision systems use the same public contracts.

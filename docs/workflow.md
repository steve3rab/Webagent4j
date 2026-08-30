# Workflows

`webagent4j-workflow` orchestrates short deterministic sequences of actions and literal assignments with typed variables, guarded steps, fail-fast execution, and secret-aware framework diagnostics. It is not a general programming language.

## Execution model

```text
Workflow (immutable definition)
      |
WorkflowEngine.execute(workflow, inputs)
      |
preflight input validation
      |
steps in declaration order
      |
condition false -> SKIPPED
condition true  -> execute step once
success         -> publish declared output, continue
failure         -> FAILED step, remaining NOT_RUN, stop
```

The engine creates fresh per-execution session state. Reusing a workflow does not reuse variables, discovered secrets, or step results.

## Definition validation

Build-time validation rejects invalid IDs/duplicates, empty step lists, conflicting input/output variable declarations, and statically invalid condition variable references. A workflow therefore contains at least one step before execution can exist.

## Variables

Variables are typed `WorkflowVariable<T>` values rather than string/object maps. Public values and secret string variables are explicit. Values are non-null; use `Optional<T>` when absence is part of the application model.

Variables are write-once within one execution:

- input builders reject duplicate assignment of the same logical variable name;
- a step output cannot overwrite an input or an earlier output;
- output runtime type is checked before publication.

## Inputs and preflight failure

Required, optional, type/sensitivity, and undeclared-input checks run before step 0. A preflight failure does not execute any step/action factory; the result is FAILED with every step `NOT_RUN` and the appropriate input failure type.

Surplus/typo inputs are rejected rather than silently ignored.

## Conditions

Built-in conditions cover presence/absence, equality/inequality, boolean checks, negation, all/any composition. Missing-variable behavior is explicit: only existence checks treat missing as their normal subject; other value-dependent conditions fail closed.

`IWorkflowCondition` is also a trusted Java extension point. Custom conditions must be deterministic, side-effect-free, accurately report referenced variables, and avoid secret leakage in descriptions. Malformed/throwing condition callbacks become structured condition failures according to the engine contract.

## Action steps

An action step receives current variables through an `IWorkflowActionFactory`, prepares one `IPreparedAction`, and executes the normal action pipeline. Workflow adds no hidden retry and does not cache a live `IActionPlan` inside reusable definitions.

Action result projection preserves the exact action status/execution/failure matrix defined in [contracts.md](contracts.md#action-outcome-matrix).

## Fail-fast result shape

A completed result contains only `SUCCEEDED`/`SKIPPED` steps. Runtime failure produces zero or more successful/skipped predecessors, exactly one failed step, and `NOT_RUN` successors. Overall failure and the failed step identify the same failure point.

A failed step publishes no output.

## Secret variables and redaction

Secret variables are string-valued and explicit. Framework-owned incidental renderings mask known secret text. The engine redacts before bounding diagnostic text and considers secret values discovered during execution when finalizing retained safe descriptions/outputs.

Explicit typed variable/result retrieval returns the real value because steps/callers may legitimately need it. This guarantee is therefore **rendering safety**, not encryption, storage security, heap protection, or protection against custom application code that logs the secret itself.

Do not place secrets in caller-controlled metadata IDs/names merely because workflow rendering is otherwise secret-aware.

## Resource-bounded diagnostics

Rendering an arbitrary caller-supplied value for diagnostics is always crash-safe, but a value's `toString()` can itself allocate heavily or run slowly - that cost is inherent to what the caller chose to render, not something the framework can eliminate. What the engine bounds is how long it *retains* a rendered result: a built-in condition (`WorkflowConditions`) defers rendering its comparison literal until the workflow's complete secret set is known, so the potentially large rendered text is created, redacted, and bounded in one step rather than held unbounded for the rest of execution. A custom `IWorkflowCondition`'s own `describe()` text is retained as returned, exactly as before, since the engine cannot defer code it does not own. Either way, `render → redact → bound` is never reordered: bounding before redaction could truncate a secret mid-value and leak a still-identifying partial fragment.

## Failure object boundary

`WorkflowFailure` carries a stable type, safe bounded message, optional step ID, and limited structural metadata. It does not expose the arbitrary raw `Throwable` object/message/stack trace through the workflow result.

## Concurrency/resources

Workflow definitions are immutable/reusable. Each execution has isolated session state. Live pages/actions referenced by application factories remain application-owned and caller-confined; Workflow neither closes nor caches them.

## Deliberate exclusions

No loops, recursion, parallel branches, DAG scheduler, transactions/sagas, persistence/checkpoints, timers/scheduling, external triggers, workflow-wide cancellation/timeout, YAML/JSON workflow DSL, or hidden retry.

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

Build-time validation rejects invalid IDs/duplicates, empty step lists, conflicting input/output variable declarations, and statically invalid condition variable references. A workflow therefore contains at least one step before execution can exist. Step ID uniqueness and variable-reference validation apply across the whole step tree, including inside every `ifElse`/`ifThen` branch at every nesting depth - not only among top-level steps.

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

## Branching

`WorkflowSteps.ifElse(id, condition, thenSteps, elseSteps)` and `WorkflowSteps.ifThen(id, condition, thenSteps)` add one deterministic conditional step: a workflow condition is evaluated exactly once when its conditional step is reached, and the resulting branch decision is immutable for that execution:

```text
reach conditional step
      |
condition evaluated exactly once
      |
decision captured
      |
exactly one branch executes (thenSteps if true, elseSteps - or nothing, for ifThen - if false)
      |
continue with the step that structurally follows the conditional
```

- **Evaluate-once:** the condition is never re-evaluated after the decision is captured, regardless of what happens afterward - a mutated variable, a later action failure, or a target-identity change inside the selected branch never triggers re-evaluation or a switch to the other branch.
- **Exactly one branch, never a fallback:** failure of the selected branch never causes the other branch to execute. `elseSteps` is not an error handler; it only ever runs because the condition evaluated to `false`.
- **Zero side effects from the non-selected branch:** the branch not selected produces zero step executions, zero action-factory calls, and zero backend invocations. It is not run for validation, dry-run, or as a fallback.
- **Fail-closed condition failure:** if the condition's own evaluation fails (throws, or its `describe()` is malformed), the conditional step fails with `CONDITION_EVALUATION_FAILED` and neither branch runs - a failed evaluation is never treated as a `false` decision.
- **Interruption:** `WorkflowEngine` has no workflow-wide timeout or budget of its own (see "Deliberate exclusions" below) - every deadline in this codebase is enforced by the action/browser backend layer for the action it wraps. A conditional step's own two structural boundaries - before the condition is evaluated, and after the decision is captured but before the selected branch starts - are instead guarded by the executing thread's interrupt status, exactly like the action pipeline's own equivalent boundary checks: an interrupt observed at either point fails the conditional step closed with `CONDITIONAL_STEP_INTERRUPTED`, and the flag is left set, never silently cleared.
- **Nested branching:** `thenSteps`/`elseSteps` may themselves contain `ifElse`/`ifThen` steps to any depth; each level follows the identical evaluate-once/exactly-one-branch contract independently. Step ID uniqueness is enforced across the whole tree (see "Definition validation" above).
- **Result shape:** a conditional step's own `WorkflowStepResult` carries the branch decision in its `condition()` field (its outcome is never used to derive `SKIPPED` for this step type - only `ifElse`/`ifThen`'s own missing-else no-op path and `SUCCEEDED`/`FAILED` apply) and publishes no output variable or action summary of its own. `WorkflowResult.steps()` stays one flat, execution-ordered list: the conditional step's own result is immediately followed by whichever single branch's steps actually ran, in order - the branch that did not run contributes nothing to the list, at any nesting depth.
- **Conditions stay pure:** a branch condition is the same `IWorkflowCondition` used for step guards elsewhere - deterministic and side-effect-free. This feature does not let a condition perform a side-effecting action.

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

No loops, recursion (over data - nested `ifElse`/`ifThen` structure is not recursion), parallel branches, DAG scheduler, transactions/sagas, persistence/checkpoints, timers/scheduling, external triggers, workflow-wide cancellation/timeout, YAML/JSON workflow DSL, or hidden retry. Deterministic if/else branching (`ifElse`/`ifThen`) is supported; workflow variables, `switch`/`case`, loops, and condition-driven exception handling are not.

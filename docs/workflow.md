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

**Guard-aware, path-sensitive definite assignment:** an output from a step guarded by `when(...)` is not definitely assigned after that step, because the guard may evaluate to `false` and skip the producer entirely - the step itself remains perfectly valid and still publishes normally whenever its guard does evaluate `true`, but nothing downstream may statically rely on it having run. Definite assignment is path-sensitive across `ifElse`/`ifThen` (see [Branching](#branching)) and guard-sensitive for ordinary steps: a later step may statically require an output - in its own `when(...)` condition, or a conditional step's own branch selector - only when every reachable control-flow path from the start of the workflow guarantees its publication. This is deliberately a purely structural analysis: the builder never attempts to prove a particular condition instance is always `true` or `false`, so guarding a producer with a condition a caller knows is always true at runtime does not exempt it - conditions are evaluated only at runtime. A guarded producer can also never "free up" its output name for a second, unconditional producer to reuse: at runtime the guard may still evaluate `true`, so both remain rejected as a structural collision regardless of definite assignment. This is not a general compiler-grade dataflow analysis - it stays limited to the workflow's own declared structure.

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

Built-in conditions cover presence/absence, equality/inequality, boolean checks, negation, all/any composition. Missing-variable behavior is explicit: only existence checks treat missing as their normal subject; other value-dependent conditions fail closed. That runtime tolerance is orthogonal to build-time definite assignment (see [Definition validation](#definition-validation)): even `exists`/`notExists` may only statically reference a variable the builder can prove is definitely present - including one produced by a `when(...)`-guarded step - since the builder deliberately never reasons about which specific condition types can safely tolerate absence.

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
- **Nested branching:** `thenSteps`/`elseSteps` may themselves contain `ifElse`/`ifThen` steps, nestable up to the framework's supported conditional nesting limit (64 levels: a top-level conditional is depth 1, one nested inside either of its branches is depth 2, and so on - `thenSteps` and `elseSteps` are measured independently, never summed, so each branch may reach the limit on its own regardless of how deep the other one goes). Each level follows the identical evaluate-once/exactly-one-branch contract independently. `Workflow.Builder#build()` rejects a definition nested deeper than the limit with a controlled `IllegalArgumentException`, before any execution exists - never a `StackOverflowError`. Step ID uniqueness is enforced across the whole tree (see "Definition validation" above).
- **Definite assignment for branch outputs:** a step output declared inside `thenSteps` or `elseSteps` is available to whatever structurally follows the conditional only when *every* reachable branch guarantees a compatible declaration of it (same name, same type, same secret status) - never merely because *some* branch might produce it. For `ifElse`, that means both `thenSteps` and `elseSteps` must declare the exact same output; for `ifThen`, a `thenSteps`-only output is never available afterward, since a `false` decision means `thenSteps` never ran at all. This is an intersection of what both branches definitely guarantee, not a union of what either branch might individually produce - a later step or condition statically referencing an output only one branch declares is rejected at build time, the same way referencing any other undeclared variable is. A same-named output declared with a conflicting type or secret status between the two branches is rejected outright, exactly like any other conflicting redeclaration (see "Definition validation" above). This composes with guard-aware definite assignment (see "Definition validation" above): a branch only counts as unconditionally guaranteeing an output when every producer of it on that branch's own reachable path is itself unguarded - a `when(...)`-guarded producer anywhere inside a branch, at any nesting depth, makes that whole branch (and therefore the enclosing conditional) unable to guarantee the output, propagating outward the same way at every level with no special-cased logic per depth.
- **Result shape:** a conditional step's own `WorkflowStepResult` carries the branch decision in its `condition()` field (its outcome is never used to derive `SKIPPED` for this step type - only `ifElse`/`ifThen`'s own missing-else no-op path and `SUCCEEDED`/`FAILED` apply) and publishes no output variable or action summary of its own. `WorkflowResult.steps()` stays one flat, execution-ordered list: the conditional step's own result is immediately followed by whichever single branch's steps actually ran, in order - the branch that did not run contributes nothing to the list, at any nesting depth.
- **Conditions stay pure:** a branch condition is the same `IWorkflowCondition` used for step guards elsewhere - deterministic and side-effect-free. This feature does not let a condition perform a side-effecting action.

## Bounded loops

`WorkflowSteps.loop(id, continueCondition, maxIterations, body)` adds one deterministic, explicitly-bounded repetition control-flow step - never a disguised repeat-until-success mechanism:

```text
reach loop step
      |
      v
continuation condition evaluated exactly once  <---------------------+
      |                                                               |
   true? --- no --> stop (SUCCEEDED, no-op for this attempt)          |
      |                                                               |
     yes                                                              |
      |                                                               |
iteration count >= maxIterations? --- yes --> LOOP_ITERATION_LIMIT_EXCEEDED (fail closed)
      |
     no
      |
      v
   body runs to completion ---------------------------------------->--+
```

- **Mandatory, framework-bounded `maxIterations`:** every loop declares a positive iteration bound, checked against a framework-wide maximum (`Workflow.MAX_LOOP_ITERATIONS`) at build time. There is no unbounded or "run until the condition says stop, however long that takes" mode.
- **Evaluate-once per iteration attempt:** the continuation condition is evaluated exactly once per attempt, never re-evaluated while that iteration's body runs, and never evaluated speculatively for a future iteration.
- **Fail-closed at the bound:** if the condition still evaluates `true` once `maxIterations` iterations have already run, the loop fails with `LOOP_ITERATION_LIMIT_EXCEEDED` rather than silently stopping - reaching the bound while continuation is still requested is a workflow failure, never a quietly accepted success.
- **No hidden retry:** a body failure stops the whole workflow immediately, exactly like any other failure - the failed step is never retried, the condition is never re-checked, and no further iteration is ever attempted.
- **Zero side effects from an iteration that never started:** exactly mirroring branching's own non-selected-branch guarantee, an iteration the engine never reached because the condition was false, the bound was hit, or execution was interrupted first contributes zero step executions, zero action-factory calls, and zero backend invocations - never a placeholder up to `maxIterations`.
- **Interruption:** identical two boundaries to a conditional step, applied per iteration attempt - before the condition is evaluated, and after the decision is captured but before the body starts - each guarded by the executing thread's interrupt status and failing the check closed with `LOOP_STEP_INTERRUPTED` if observed.
- **Combined control-flow nesting depth:** a loop nested inside a conditional branch, or a conditional nested inside a loop body, all count toward the exact same shared nesting-depth bound (`Workflow.MAX_CONTROL_FLOW_NESTING_DEPTH`, equal in value to the existing conditional-only limit) - never two independently-tracked limits.
- **Cumulative executed-node budget:** beyond any single loop's own `maxIterations`, `WorkflowEngine` enforces a cumulative budget (`MAX_EXECUTED_WORKFLOW_NODES`) across the whole execution, failing closed with `EXECUTED_NODE_BUDGET_EXCEEDED` - this is what stops a nested-loop structure that is locally within every individual bound yet combinatorially explosive once multiplied together (for example, an outer loop of 100 wrapping an inner loop of 100).
- **Loop body outputs are never definitely available afterward:** a step inside `body` that declares an output is validated for structural collisions exactly like any other step, and its value remains readable after the loop through the ordinary `WorkflowOutputs`/`WorkflowResult#output(...)` lookup - but it is never treated as *definitely* available to a later step's own condition, since the loop may run zero iterations. This is the identical guard-aware definite-assignment rule already documented above for a `when(...)`-guarded producer, applied to a loop body as a whole rather than introducing a second mechanism.
- **Result shape:** a loop produces one wrapper `WorkflowStepResult` (`WorkflowStepType.LOOP`, always `SUCCEEDED` when reached - it never itself reports a nested failure, exactly like a conditional's own decision node) followed by one `WorkflowStepType.LOOP_ITERATION` result per continuation check actually performed. Each `LOOP_ITERATION` is structurally identical to an `ifThen` decision: a `true` outcome selects `WorkflowBranchSelection.THEN` and carries that iteration's own body steps as children; a `false` outcome selects `NONE` as a no-op stop with zero children. `WorkflowResult.steps()` stays one flat, execution-ordered list exactly as before.
- **Conditions stay pure:** the continuation condition is the same `IWorkflowCondition` used everywhere else - deterministic and side-effect-free. A loop does not let a condition perform a side-effecting action, and nothing about looping changes what a condition is allowed to do.

## Execution tree

`WorkflowEngine#executeWithTree(workflow, inputs)` runs the exact same single execution as `execute(workflow, inputs)`, additionally returning a `WorkflowExecutionTree` - a hierarchical view of the control-flow path that actually executed, alongside the existing flat `WorkflowResult` (bundled together as `WorkflowExecution`):

```text
Workflow
├── step-1
├── branch-A (WorkflowBranchSelection.THEN)
│   ├── step-2
│   └── branch-B (WorkflowBranchSelection.ELSE)
│       └── step-3
└── step-4
```

This is a structural companion to `WorkflowResult.steps()`, not a replacement for it: `execute(...)` is unchanged, still returns exactly `WorkflowResult`, and every consumer of the existing flat list keeps working exactly as before. `WorkflowResult` is a public record, and its canonical constructor is itself public API; adding the tree as a new record component there would change that constructor's signature and break existing callers, so it is exposed through the additive `executeWithTree`/`WorkflowExecution` pair instead.

**Definition tree vs. execution tree:** a `Workflow`'s own step structure (`thenSteps`/`elseSteps` as declared) says what *could* execute. `WorkflowExecutionTree` says only what *did* execute, or was explicitly marked `NOT_RUN` on the path the engine actually reached - never a speculative or definition-derived entry. The two are related but distinct: a conditional step's non-selected branch is part of the definition tree but contributes zero nodes to the execution tree.

**Single source of truth:** the tree is built once, during the same recursive traversal that already produces the flat step-result list - never by a second interpretation of that list afterward (which is not even possible in general, since the flat list alone cannot reconstruct which conditional a given entry's parent was). Building or reading the tree never evaluates a condition, invokes an action, or selects a branch a second time. Both views share the exact same `WorkflowStepResult` instances - a step's result is computed once, never independently recreated for the tree.

**Node shape:** `WorkflowExecutionNode` carries the step's own already-safe `WorkflowStepResult` (secret-safe and bounded exactly as it already is on `WorkflowResult.steps()` - see "Secret variables and redaction" below), an `Optional<WorkflowBranchSelection>` (`THEN`, `ELSE`, or `NONE` for an `ifThen`'s no-op `false` decision - present only for a `CONDITIONAL` step whose decision was actually captured), and its `children` - the selected branch's own execution nodes, in execution order. A non-`CONDITIONAL` node always has empty children and no branch selection.

**Non-selected branch = zero execution nodes:** exactly mirroring the zero-side-effect guarantee the non-selected branch already has at runtime (see "Branching" above), it contributes nothing to the tree - not a placeholder, not a `NOT_RUN` entry, nothing. A `NOT_RUN` node only ever appears for a step that was reachable on the executed path but never got there because an earlier step on that same path failed - a fundamentally different concept from "not selected by control flow," and the tree never conflates the two.

**Interruption:** a conditional interrupted before its condition is ever evaluated has no branch selection and no children. One interrupted after the decision was captured but before the selected branch could start carries that decision as its `branchSelection` (the decision did happen) while still having zero children (the branch itself never started) - the tree can express this distinction precisely because the selection and the children are two separate fields.

**Flat/tree equivalence:** flattening `WorkflowExecutionTree#nodes()` in execution order (each node, immediately followed by its own children recursively) yields exactly the same sequence of `WorkflowStepResult`s, by reference, that `WorkflowResult#steps()` already returns.

**Not a new recording format:** Recording V1 depends only on `WorkflowResult.steps()`, which is completely unaffected. The execution tree is runtime-only in this version - it is never serialized into a `WorkflowRecording`, and calling `executeWithTree` and recording the resulting `WorkflowResult` produces a byte-identical recording to calling `execute` directly.

**Loops:** a `WorkflowStepType.LOOP` node's children are its own `LOOP_ITERATION` nodes, one per continuation check actually performed - never a branch selection of its own, since it represents no single decision. Each `LOOP_ITERATION` node follows the identical shape a `CONDITIONAL` node already has (see "Bounded loops" above): `THEN` with that iteration's own body as children, or `NONE` with zero children for the final, non-continuing check. An iteration that never started (the bound was hit, or execution stopped first) contributes nothing - never a placeholder up to `maxIterations`.

## Execution plan

`WorkflowPlanner.plan(workflow)` builds a `WorkflowExecutionPlan` - a deterministic, backend-neutral description of what a `Workflow` is structurally capable of executing, entirely from its already-validated definition:

```text
Workflow
├── step-1
├── branch (CONDITIONAL)
│   ├── THEN
│   │   └── step-2
│   └── ELSE
│       └── step-3
└── step-4
```

**Planning a workflow causes zero workflow side effects.** Building a plan never calls an `IWorkflowActionFactory`, never evaluates an `IWorkflowCondition`, never resolves or verifies a backend target, and never performs a click, fill, type, select, upload, submit, or download - it reads only static step metadata already present on the definition (step ID, `WorkflowStepType`, whether a step carries an optional guard, its declared output variable, and a conditional's `thenSteps`/`elseSteps` structure). `WorkflowPlanner` is a dedicated type, kept separate from `WorkflowEngine`, so planning and execution never share a code path.

**Execution plan vs. execution tree:** these are deliberately two distinct types, never merged and never toggled between with a flag. `WorkflowExecutionPlan` describes every structurally possible path through a definition, before any execution exists - it never claims a runtime-dependent action, condition, policy, or target verification will succeed, since it cannot know that. `WorkflowExecutionTree` (see above) describes the one path a specific execution actually took. A plan can be built for a `Workflow` that has never been executed at all.

**Branch completeness:** unlike the execution tree, where a conditional's non-selected branch contributes zero nodes, a plan represents *both* of a conditional's structurally possible branches - the condition is never evaluated to decide which one to show. An `ifElse` plan node always carries a `THEN` branch and an `ELSE` branch; an `ifThen` plan node always carries a `THEN` branch and a `NONE` branch (the structurally absent else - a false decision's potential no-op outcome, never invented content). `WorkflowBranchSelection` is reused for this branch label, but the plan never "selects" one - both are always present.

**Guards are marked, never evaluated:** a step built with `.when(condition)` is marked `guarded = true` on its plan node - conditionally executable, never "will execute" - without ever calling the guard's `evaluate(...)`.

**Typed output declarations:** a plan node exposes its step's declared output as a `WorkflowPlanOutput` (name, type name, and `PUBLIC`/`SECRET` classification) - metadata only, never a value. A `CONDITIONAL` node never declares an output.

**No invented outcomes:** the plan never exposes a policy decision, a target-resolution result, or any other runtime-dependent verdict - there is no such field on `WorkflowPlanNode` at all, since planning cannot know what a real execution's policy evaluation or target verification would decide.

**Resource bounds:** a plan's node count is proportional to the number of steps the definition declares - nested conditionals are represented as a tree, never expanded into every combination of branch outcomes as an independent list. Nesting is bounded by the same `Workflow.MAX_CONTROL_FLOW_NESTING_DEPTH` every `Workflow` is already bounded by, so building a plan for a definition at the maximum nesting depth never risks a `StackOverflowError`.

**Loops:** a `LOOP` plan node carries exactly one `THEN` branch, describing `body` structurally once - never unrolled into `maxIterations` copies, regardless of how large the declared bound is. Planning stays `O(definition nodes)` even for a deeply nested loop; the bound itself is not part of the plan (see "Bounded loops" above for why), only the body's own structure.

**Determinism:** two plans built from the same `Workflow` are always logically equal - no random UUID, timestamp, or hash-order-dependent iteration is ever part of a plan's shape.

**Not a new recording format:** Recording V1 is entirely unaffected; `WorkflowExecutionPlan` is never serialized into a `WorkflowRecording`. Planning is not a dry run, a replay, or an execution-plan preview of a specific future execution's outcome - it is a static structural description only.

## Validation report

`Workflow.Builder#validate()` explains the builder's *current* definition state as a structured `WorkflowValidationReport`, without ever throwing and without mutating the builder - calling `validate()` any number of times, in any order relative to `step`/`requiredInput`/`optionalInput`/`build()`, never changes the builder's own state or a later call's result for the same state:

```text
WorkflowValidationReport
├── valid()                                  // diagnostics().isEmpty()
├── diagnostics: [WorkflowValidationDiagnostic...]
├── requiredInputs / optionalInputs: [WorkflowVariable...]
├── outputs: [WorkflowValidationOutput...]   // producer step, variable, definitelyAvailable
├── stepCount / conditionalCount
└── maximumObservedConditionalDepth
```

**Three related, deliberately separate concepts:** `WorkflowValidationReport` explains *whether and why a definition is valid, before any execution exists*. `WorkflowExecutionPlan` (see above) explains *what a valid definition can structurally execute*. `WorkflowExecutionTree` explains *what one specific execution actually did*. None of the three depends on either of the others, and none is ever merged into or toggled from another with a flag.

**Single source of truth:** `validate()` and `build()` derive their conclusions from the exact same internal analysis (`Workflow.Builder#analyze`) - the same recursive step-tree traversal, the same guard-aware definite-assignment rules, the same `MAX_CONDITIONAL_NESTING_DEPTH` bound. There is no second, independently maintained validation algorithm that could drift out of sync with `build()`'s own rules: a definition `build()` accepts always produces `report.valid() == true`, and one it rejects always produces at least one diagnostic. `build()` remains fully fail-closed - it always rejects an invalid definition, whether or not a caller ever calls `validate()` first, and the report never makes an invalid definition executable.

**Zero side effects:** like `build()`, producing a report never calls an `IWorkflowActionFactory`, never evaluates an `IWorkflowCondition` (`evaluate()` is never invoked - only the side-effect-free `referencedVariables()` metadata method `build()` already reads today is read), and never touches a backend, browser, or network resource.

**Fail-fast vs. accumulate:** `build()` throws on the very first invariant violation it encounters, exactly as before. `validate()` instead continues analyzing every remaining structurally independent part of the definition it can safely reach, so a caller sees every diagnostic reachable from that continued walk in one report - but it never resumes interpreting a step (or a conditional's branches) whose own violation would make trusting its contribution unsafe: such a step's contents are simply skipped, and the walk continues with whatever structurally follows it. For example: a duplicate step ID or an over-depth conditional skips that step's own condition/output/branches entirely (nothing about its contents can be trusted); a malformed condition metadata result skips only that condition's own reference check, but the step's own output and everything that follows are still analyzed; a conflicting or colliding output declaration is simply never registered, and analysis continues with the next step.

**Definite assignment, exactly as build-time:** the report distinguishes a *declared* output from one that is *definitely available* - guaranteed to have been published by the time execution reaches whatever structurally follows its producer. A guarded (`when(...)`) producer's output is declared but never definite. An `ifElse` output is definite only when both branches unconditionally guarantee it (an intersection, never a union); an `ifThen` output is never definite afterward, since its `thenSteps` may not have run at all. These are the identical rules [Definition validation](#definition-validation) and [Branching](#branching) already document - `validate()` does not reinterpret them.

**Structured diagnostics:** each `WorkflowValidationDiagnostic` carries a stable `WorkflowValidationCode` (`EMPTY_STEP_LIST`, `DUPLICATE_INPUT_DECLARATION`, `DUPLICATE_STEP_ID`, `CONDITIONAL_DEPTH_EXCEEDED`, `CONDITION_METADATA_INVALID`, `OUTPUT_NOT_DEFINITELY_AVAILABLE`, `OUTPUT_COLLISION`, `OUTPUT_TYPE_MISMATCH`, `OUTPUT_SECRET_CLASSIFICATION_MISMATCH`, `LOOP_INVALID_MAX_ITERATIONS`, `LOOP_NESTING_DEPTH_EXCEEDED`), a `WorkflowValidationSeverity` (`ERROR` only in this version - every diagnostic corresponds to an invariant `build()` already enforces as fail-closed, so there is no separate warning/informational tier), the step ID and/or variable name it concerns when applicable, and a safe message. Diagnostic order is deterministic: definition-traversal order, never `HashMap`/`HashSet` iteration order.

**Resource bounds:** diagnostics are capped at `MAX_VALIDATION_DIAGNOSTICS` (256); once reached, `WorkflowValidationReport#diagnosticsTruncated()` is set and further diagnostics are discarded rather than retained without bound. `build()` never accumulates more than one diagnostic, since it throws on the first.

**Secret safety:** `requiredInputs`/`optionalInputs`/`outputs` expose only a variable's name, declared runtime type, and secret classification (`WorkflowVariable` itself) - never a value. No diagnostic message ever contains a raw value, a secret, or a `Throwable`.

**Not a new recording format:** Recording V1 is entirely unaffected; `WorkflowValidationReport` is never serialized into a `WorkflowRecording`.

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

No recursion (over data - nested `ifElse`/`ifThen`/`loop` structure is not recursion), parallel branches or parallel iterations, DAG scheduler, transactions/sagas, persistence/checkpoints, timers/scheduling, external triggers, workflow-wide cancellation/timeout, YAML/JSON workflow DSL, arbitrary mutable inter-iteration loop state, or hidden retry. Deterministic if/else branching (`ifElse`/`ifThen`) and bounded, explicitly-limited repetition (`loop`, see "Bounded loops" above) are supported; `switch`/`case`, unbounded loops, and condition-driven exception handling are not.

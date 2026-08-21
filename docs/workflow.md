# Workflow

Phase 0.8: a deterministic, sequential orchestration layer over `webagent4j-action` - typed
variables, masked secrets, simple declarative conditions, and a structured result. It is not a
general programming language.

## Purpose

Real automations are rarely a single action: log in, then act, then verify. `webagent4j-workflow`
lets a caller compose a small, named, reusable sequence of steps - each one either a real
`webagent4j-action` command or a deterministic literal assignment - over a set of typed,
optionally-secret variables, and get back a structured, secret-safe result instead of writing that
orchestration by hand with local variables and `if` statements.

## When to use

Use it when you have a short, linear sequence of actions that should be defined once and reused
(a login flow, a form submission followed by verification) and you want structured, inspectable
results instead of throwing on the first failure and losing everything else that happened. Do not
reach for it to express branching logic, loops, retries, or anything that needs more than "run this
list of steps in order, stop at the first failure" - see [Non-goals](#non-goals).

## Architecture

```text
Workflow.Builder -> Workflow (immutable definition)
                        |
                        v
     WorkflowEngine.execute(workflow, inputs)
                        |
                        v
              Session (per-call mutable state, single-threaded)
                        |
                        +-- variables (WorkflowVariable -> value, seeded from inputs)
                        +-- SecretRedactor (rebuilt from currently-known secret values)
                        +-- outputs (WorkflowOutputs.Builder)
                        +-- step results, in definition order
                        |
              for each step, in order:
                condition present? evaluate -> false: SKIPPED, continue
                run step exactly once
                  ACTION step -> IWorkflowActionFactory.prepare(vars) -> IPreparedAction.execute()
                  ASSIGN step -> the literal value
                success -> publish output (if declared), continue
                failure -> WorkflowResult FAILED, remaining steps recorded NOT_RUN, stop
                        |
                        v
                  WorkflowResult (WorkflowId, status, steps, outputs, failure)
```

`WorkflowEngine` mirrors the per-call `Session` pattern `BrowserCrawler` uses: the engine itself is
stateless and reusable; every `execute()` call gets a private, fresh session, so two executions of
the same `Workflow` - even back to back - never share variable state, discovered secrets, or step
results.

## Workflow definitions

A `Workflow` is an immutable, reusable sequence of `IWorkflowStep`s over a declared set of typed
inputs, built through `Workflow.builder(id)`. Building a workflow is entirely side-effect-free: it
never touches a backend, never calls an `IWorkflowActionFactory`, and never performs any I/O - only
structural validation.

`Workflow.Builder#build()` rejects, before any execution ever happens:

- a blank workflow ID, or an empty step list;
- a duplicate step ID (no auto-suffixing - the caller must choose unique IDs);
- the same input variable name declared more than once among required and optional inputs
  combined - even if the two declarations are identical;
- a step's declared output colliding with an existing input or an earlier step's output (variables
  are write-once - see [Variables](#variables));
- a condition whose `referencedVariables()` is null, contains a null entry, throws, or references a
  variable that is neither a declared input nor produced by an earlier step (a static, linear
  dataflow check - see [Conditions](#conditions)). These errors are built from the step ID and
  variable name only, never from the condition's `describe()` - secrets may not even be known yet
  at build time.

A `Workflow`'s own `toString()` is safe: it shows the ID, every declared input's name (annotated
`(optional)`/`(secret)` as appropriate, never a value), and every step ID - never a captured
execution value.

## Variables

There is no `Map<String, Object>` anywhere in the public API. Every value is read and written
through a `WorkflowVariable<T>`, created via `WorkflowVariable.publicValue(name, Class<T>)` or
`WorkflowVariable.secret(name)`. A variable's declared `Class<T>` is checked against every value
that flows through it (`instanceof`, not a full generic `TypeToken` framework - for a generic
container type such as `List<String>`, only the raw `List` shape is verified, never its element
type). A `null` value is always rejected; if you need "no value yet" semantics, declare the
variable's type as `Optional<T>` and store an explicit `Optional`.

Two variables are the same logical variable only when their name, type, and secret status all
agree - `Workflow.Builder#build()` rejects a workflow that reuses one name with a different type or
sensitivity, so a name can never silently mean two different things.

**Write-once.** A `WorkflowInputs.Builder` rejects a second `put()` call for a variable name that
was already supplied, regardless of whether the value or declaration matches the first - a caller
cannot silently overwrite an input it already provided. A step may add a new output variable, but
it can never overwrite an existing input or an earlier step's output; this half is enforced
structurally, at `Workflow.Builder#build()` time, not by a runtime check, because a step's declared
output name is validated against every input and every earlier step's output before the workflow
definition can even exist.

## Required and optional inputs

`Workflow.Builder#requiredInput(variable)` declares a variable that must be supplied; `execute()`
validates every required input *before* step 0 ever runs. If one is missing, or was supplied under
a `WorkflowVariable` with a different type or secret status, execution never begins: no step runs,
no action factory is called, and the engine returns a structured `WorkflowResult` with
`WorkflowStatus.FAILED` (`MISSING_REQUIRED_INPUT` or `INPUT_TYPE_MISMATCH`) rather than throwing.

`Workflow.Builder#optionalInput(variable)` declares a variable that may or may not be supplied.
There is no implicit default, no environment-variable lookup, and no system-property fallback -
inputs are explicit only. If an optional input is supplied under a `WorkflowVariable` with a
different type or secret status than declared, that is validated *before* step 0 too, exactly like
a required input's mismatch - it never silently falls through as "not supplied" (which would make
`notExists()` observe a presence mismatch as absence and quietly change which steps run).
Conditions typically branch on an optional input's presence with `exists`/`notExists` (see
[Conditions](#conditions)).

Any `WorkflowInputs` entry whose name is not declared as a required or optional input of the
workflow being executed also fails before step 0, as `UNDECLARED_INPUT` - a surplus, typo'd, or
stale input (including an unused secret) is rejected rather than silently ignored. The failure
message names only the variable, never its value.

## Secret variables

`WorkflowVariable.secret(name)` declares a `String`-valued variable whose value must be masked in
every incidental, framework-owned rendering. Secrets are deliberately restricted to `String`
values: that keeps the masking contract precise, since an arbitrary object's own `toString()`
cannot be trusted to render safely. Secret status is always explicit at variable declaration - never
inferred from a variable's name (no `if (name.contains("password"))` heuristics).

`WorkflowSteps.assign()` refuses to accept a secret variable: a literal assigned inside a workflow
*definition* would live permanently inside that immutable, reusable object. Prefer supplying a
secret as a `WorkflowInputs` value (fresh per execution) or as a secret-declared action output.

### Secret masking

**Explicit typed retrieval always returns the real value** - `IWorkflowVariables#require`/`#find`
inside a running step, and `WorkflowResult#output(variable)` after execution - because a step
genuinely needs to type a password into a form, and a caller who explicitly asked for a specific
typed output is not the incidental-rendering case this contract protects against.

**Every incidental, framework-owned rendering masks the value as `***`** - never a length-preserving
or partial mask (no first/last-N-characters). This applies to `WorkflowVariable#toString()`,
`WorkflowInputs#toString()`, `WorkflowOutputs#toString()`, `WorkflowResult#toString()`,
`WorkflowStepResult#toString()`, `WorkflowFailure#toString()` (including its `safeMessage`), and
every `IWorkflowCondition#describe()` built through `WorkflowConditions`.

**Redaction is centralized, not scattered.** `WorkflowEngine`'s internal `SecretRedactor` is built
fresh, per execution, from whatever secret string values are currently known to that execution's
session, and applied to every failure message the engine constructs (an action-factory exception, a
failed `ActionResult`'s own message, a condition evaluation exception, and any other structured
failure) - individual step implementations never redact anything themselves. A secret-declared
output produced by an early step is added to the session's known-secret set immediately on
publication, so a *later* step's failure message is redacted against it too, not just steps that
run after the value was typed somewhere. Redaction always happens before bounding/truncation, never
after - truncating first could cut a secret's raw text in half and leak a partial, still-identifying
fragment through the "public" remainder. If two known secrets overlap as substrings (for example
`"abc"` and `"abcdef"`), the longer one is matched first, so a shorter secret's redaction can never
leave a partial fragment of a longer one behind. This is also why a built-in condition's own
`describe()` (see [Conditions](#conditions)) renders its public literal comparison value without
bounding it: bounding happens only once, after that text has already been redacted - a condition
literal that is pre-truncated before redaction could otherwise split a secret across the truncation
boundary and leak a fragment of it.

**A retained condition description is finalized at workflow termination, not at evaluation time.**
`condition.describe()` is still called at most once, right when its step's condition is evaluated,
but a `SKIPPED` or `SUCCEEDED` step's resulting `WorkflowConditionResult` is not built until the
workflow terminates - it is redacted against every secret the execution ever discovers, all the way
through its last step (or its point of failure), then bounded. This lets a secret revealed by a
*later* successful step retroactively mask an *earlier* step's already-recorded condition
description, exactly like it already retroactively masks an earlier public output (see below). A
`WorkflowFailure`'s own message is the one exception: it is redacted and bounded immediately,
because a step failure terminates execution right there - no later step can ever run, so no later
secret can ever need to redact it.

**Redaction is cross-field, not per-field.** `WorkflowInputs#toString()` and
`WorkflowOutputs#toString()` do not decide masking per value based only on that value's own
declared secrecy: each redacts every rendered value - including public ones - against every secret
value known at the point rendering is computed. A public field whose value happens to textually
contain a known secret's raw text (for example a "notes" field that was accidentally given the
password string, or a public action output whose text happens to embed a secret input's value) is
redacted too, not just the field declared secret. For example, given a secret input `PASSWORD =
"hunter2"` and a public action output `MESSAGE = "used hunter2"`, incidental rendering shows
`message=used ***`, while `result.output(MESSAGE)` (explicit typed retrieval) still returns the raw
`"used hunter2"` string.

`WorkflowInputs` is self-contained: it redacts against the secret values among its own supplied
entries, which already include every required and optional secret input the caller provided.
`WorkflowOutputs`, however, cannot see a workflow's inputs on its own - its safe rendering is
computed once, when `WorkflowEngine` assembles the final `WorkflowResult` (for both a `COMPLETED`
and a `FAILED` outcome), from *every* secret value known to that execution at that point: every
secret input, and every secret output any step successfully published before termination. This
also means a public output published early is still masked in the final result if a *later* step
reveals that same text as a secret before the workflow ends - the safe preview is not permanently
fixed at the moment each output is published.

**No arbitrary raw `Throwable` is ever exposed** through `WorkflowFailure`/`WorkflowResult`: a
step's own thrown exception could carry a secret in its message (`new RuntimeException("bad
credential " + password)`), and returning that `Throwable` would let a caller bypass every masking
guarantee with a single `getMessage()` call. `WorkflowFailure` carries a stable category, an
already-redacted safe message, and at most the failing exception's *class name* - never its message
or stack trace.

### The limit of this guarantee

This is masking of framework-owned representations, not encryption or a vault. WebAgent4J can
guarantee that its own rendering never leaks a secret; it cannot prevent user-supplied step code
from doing `System.out.println(secret)` itself, and it cannot control what a third-party backend
logs on its own. Debugger memory inspection, heap dumps, and JVM-level instrumentation are outside
this guarantee entirely.

## Conditions

`WorkflowConditions` supplies the built-in declarative conditions listed below - never an arbitrary
`Predicate` or a scripting/expression language. Unlike `IWorkflowStep` (see [Steps](#steps)),
`IWorkflowCondition` is also a trusted Java extension point: nothing prevents implementing it
directly. A custom condition must be deterministic, side-effect-free, accurately report every
variable it reads from `referencedVariables()`, and must not intentionally expose a secret from
`describe()`. A description stored in a `WorkflowConditionResult` is always redacted - against every
secret known by workflow termination, not only those known when the condition was evaluated (see
[Secret masking](#secret-masking) above) - and length-bounded by `WorkflowEngine` before it is
stored; calling `describe()` directly, outside `WorkflowEngine`, returns the condition's own text
as-is - for a built-in condition, that is a crash-safe rendering of its public literal, deliberately
not yet bounded. `WorkflowEngine` treats every
method on a condition it did not create as caller-supplied code and handles it defensively: an
`evaluate()`/`describe()` that throws becomes a structured `CONDITION_EVALUATION_FAILED` rather than
propagating; a `describe()` returning `null` is
treated the same way; a `describe()`'s text is still redacted against every currently-known secret
as defense-in-depth, even though a well-behaved condition should never have put one there; and a
`referencedVariables()` that is null, contains a null, or throws is rejected at
`Workflow.Builder#build()` time as `IllegalArgumentException`, before any execution can begin.

`not`, `allOf`, and `anyOf` do not hide failures from a wrapped custom condition: a wrapped
`describe()` that returns `null` or throws is still observed by `WorkflowEngine` as a malformed
condition and becomes `CONDITION_EVALUATION_FAILED` - the combinator never normalizes a `null` child
description into the literal text `"null"` through string concatenation, and it stops composing
further child descriptions as soon as one is malformed, calling each child's `describe()` at most
once.

| Condition | Missing-variable semantics |
|---|---|
| `exists(variable)` | tolerates missing - returns `false` |
| `notExists(variable)` | tolerates missing - returns `true` |
| `equals(variable, expected)` | **fails closed** - throws, becomes `CONDITION_EVALUATION_FAILED` |
| `notEquals(variable, expected)` | fails closed |
| `isTrue(booleanVariable)` | fails closed |
| `isFalse(booleanVariable)` | fails closed |
| `not(condition)` | delegates to the wrapped condition |
| `allOf(condition...)` / `anyOf(condition...)` | delegates to every composed condition |

`exists`/`notExists` are the only conditions that tolerate a missing variable, because that is
literally what they test for. Every other condition treats a missing variable as an evaluation
failure rather than silently coercing it to `null` or `false` - an accidentally-missing variable
stays visible instead of quietly changing which branch runs.

A built-in condition's `describe()` is always safe to render (secret values are masked at the point
the condition is built, via the same rendering rule used everywhere else), and its
`referencedVariables()` lets `Workflow.Builder#build()` statically reject a condition that
references a variable that is neither a declared input nor an earlier step's output. Building a
workflow does call a condition's own metadata methods (`referencedVariables()`, and `describe()` at
evaluation time later) - the framework does not literally guarantee zero side effects from custom
condition code, it requires custom conditions to be written that way.

There is no `IfStep { thenSteps; elseSteps }` and no branching graph: every step is optionally
guarded (`step.when(condition)`), and the workflow itself stays one linear, ordered list. A step
whose earlier, conditionally-guarded "producer" step was skipped can guard itself with
`exists(theProducedVariable)` to skip safely too; an unguarded consumer of a variable that was
never produced fails with `MISSING_VARIABLE` when it actually runs - this is not a bug, it is the
same fail-closed philosophy applied to dataflow that cannot be verified statically because
`Workflow.Builder#build()` cannot see inside an opaque action factory.

## Steps

`WorkflowSteps` provides the complete executable step set for Phase 0.8. No custom executable step
SPI is exposed - `IWorkflowStep` is `sealed` and permits only an internal implementation type in the
same package, so a caller cannot implement it even by trying: the compiler rejects a foreign
implementation at the point it would be declared, and the JVM's `PermittedSubclasses` check rejects
one that somehow reached the classpath as compiled bytecode. This is what lets `WorkflowEngine`
treat every step accepted by a built `Workflow` as one it knows how to run, without a runtime type
check that could otherwise fail.

- **`WorkflowSteps.action(id, factory)`** / **`WorkflowSteps.action(id, factory, output)`** - a step
  backed by the real `webagent4j-action` pipeline. See [Action integration](#action-integration).
- **`WorkflowSteps.assign(id, variable, value)`** - deterministically assigns a literal, already
  type-validated, non-secret value to `variable`. No side effect, no backend call.

## Action integration

An action-backed step is defined through an `IWorkflowActionFactory<R>` - `IWorkflowVariables ->
IPreparedAction<R>` - a *preparation* factory, not the action itself. `WorkflowEngine` calls
`factory.prepare(variables)` **at most once per execution, only when the step actually runs** -
never when its condition is false, never during `Workflow.Builder#build()` or any other structural
validation - and immediately calls `IPreparedAction#execute()` on the result exactly once. This
mirrors why an `IActionPlan` can never be cached inside an immutable, reusable `Workflow`
definition: it is explicitly single-use. A workflow definition never stores a live `IActionPlan` or
`IPreparedAction`; only the factory that builds a fresh one per execution.

A factory typically closes over an already-open `IPage` (or, as in the integration tests and the
bundled example, receives it through a `WorkflowVariable<IPage>` input, which lets one `Workflow`
definition run against different pages/browser sessions). `WorkflowEngine` never creates, owns, or
closes that resource.

On success, if the step declared an output variable, the action's result value is validated against
that variable's type and published; a `null` value or a type mismatch becomes a structured failure
(`NULL_OUTPUT`/`OUTPUT_TYPE_MISMATCH`) rather than corrupting workflow state. On failure -
`ActionResult#success() == false` - the workflow step fails and no later step runs. The engine
projects only the action's correlation metadata (`ActionId`), categorical outcome fields
(`ActionType`, `ActionStatus`, `ActionExecutionMode`), and (for a failure) the
`ActionFailureType` and a safe message - never the action's raw value, observations, diagnostics,
or underlying cause. `ActionId` is supplied by the action pipeline and is not secret-redacted by
the workflow layer; it must contain non-sensitive correlation metadata. `ActionResult#executed()`
semantics are preserved end to end, but the engine never retries: a failed action may already have
performed a real side effect, and an automatic replay could resubmit, delete, pay, or confirm twice.

## Structured results

`WorkflowResult` is immutable and carries: the `WorkflowId`, an overall `WorkflowStatus`
(`COMPLETED` or `FAILED`), every step's `WorkflowStepResult` in definition order, the produced
`WorkflowOutputs`, and an `Optional<WorkflowFailure>` present exactly when the status is `FAILED`.
`WorkflowResult#output(variable)` gives typed access to a produced value (the real value, even for a
secret - see [Secret masking](#secret-masking)); `WorkflowResult#throwIfFailed()`
is an optional convenience mirroring `ActionResult#throwIfFailed()`, and the resulting
`WorkflowFailedException`'s message is built from the already-safe, already-redacted result.

Each `WorkflowStepResult` carries a `WorkflowStepStatus`:

- `SUCCEEDED` - the step executed and succeeded.
- `SKIPPED` - the step's condition evaluated to `false`; it never ran, and (for an action step) its
  factory was never invoked.
- `FAILED` - the step itself, or its condition's evaluation, failed.
- `NOT_RUN` - the workflow had already failed at an earlier step; recorded so the result always
  preserves the workflow's complete step order, even for steps that never ran.

## Failure semantics

Execution is **fail-fast only**: the first failed step stops the workflow immediately, and every
later step is recorded `NOT_RUN`. There is no `continueOnError`, no `ignoreFailure`, no best-effort
mode, and no workflow-level retry or fallback. A condition evaluating to `false` is not a failure -
it produces `SKIPPED`, and execution continues normally.

`WorkflowFailure` carries a stable `WorkflowFailureType`: `MISSING_REQUIRED_INPUT`,
`INPUT_TYPE_MISMATCH`, `UNDECLARED_INPUT`, `MISSING_VARIABLE`, `CONDITION_EVALUATION_FAILED`,
`ACTION_FACTORY_FAILED`, `ACTION_FAILED`, `STEP_EXCEPTION`, `OUTPUT_TYPE_MISMATCH`, `NULL_OUTPUT`.
Definition programmer errors (a duplicate step ID, a duplicate input declaration, a forward variable
reference, malformed condition metadata) throw `IllegalArgumentException` at build time; everything
else that can go wrong at runtime - a missing, mismatched, or undeclared input, a missing variable,
a failed action, an unexpected exception from a step or condition - produces a structured
`WorkflowResult` with `WorkflowStatus.FAILED` rather than an exception escaping `execute()`.

Precisely: expected workflow/runtime failures and `RuntimeException`s originating from supported
condition/action execution hooks are converted into structured failures. JVM `Error`s and programmer
misuse at API construction boundaries (for example, building an invalid `Workflow` definition) are
not swallowed.

## Determinism

Given the same immutable `Workflow` definition, the same inputs, the same deterministic conditions,
and the same deterministic underlying step/action outcomes, WebAgent4J guarantees: step traversal
order (definition order), condition evaluation order, caller insertion order for `WorkflowInputs`
rendering, declaration order for a workflow's inputs, publication order for `WorkflowOutputs`,
step-result order (definition order), the location of the first failure, `NOT_RUN` ordering, and the
final status are all deterministic and reproducible. Not guaranteed: wall-clock duration,
browser/backend scheduling, external page content, a third-party exception's identity, or the
rendered text of an arbitrary caller-supplied object's own `toString()` - the framework's own
iteration and publication order is deterministic, but it does not control what a user-defined
`toString()` implementation does with that position.

## Threading

Execution is synchronous and single-threaded: every step - condition evaluation, action factory,
`IPreparedAction#execute()`, output publication - runs on the exact thread that called
`WorkflowEngine#execute()`. There is no `ExecutorService`, no virtual thread, no
`CompletableFuture`, and no parallelism anywhere in the engine. This keeps a caller-owned resource
an action factory closes over (an `IPage`, for example) on the thread it expects.

## Resource ownership

`WorkflowEngine` owns nothing a caller supplied - no browser, no page, no action backend - and never
closes anything an `IWorkflowActionFactory` captures or returns. A `Workflow` definition is
reusable across sequential executions with fully independent variable state, secret registry, and
step results per call; the definition itself is immutable and safe to share, but external resources
captured by its step factories (a browser page, for instance) are not automatically thread-safe or
safe to use concurrently from two simultaneous executions - that is a property of the resource, not
something this module claims to add.

## Limitations

By design, this phase does not add: loops, `while`, `forEach`, recursion, arbitrary graph execution
or DAG scheduling, parallel branches, fork/join, workflow-level or automatic action retries beyond
the action layer's own, compensation/sagas, transactions, persistence, resumable workflows,
checkpoints, distributed execution, scheduling, cron, timers, external event triggers, a YAML/JSON
workflow DSL, a visual editor, dynamic plugin discovery, or recording/replay. There is also no
workflow-wide hard timeout and no workflow cancellation: a Java-side deadline wrapped around an
otherwise-unbounded step can be false safety (see the discussion in
[wait-and-stability.md](wait-and-stability.md)), and general cancellation is deferred until a
backend-neutral cancellation abstraction is deliberately designed rather than duplicated ad hoc from
`webagent4j-browser-crawler`'s crawl-specific token.

## Security considerations

See [Secret masking](#secret-masking) above for the exact, load-bearing
guarantee. In short: WebAgent4J protects its own rendering, not the process. Careless or malicious
step code, a debugger, a heap dump, or a third-party backend's own logging are all outside what this
module can control.

## Examples

`webagent4j-examples`' `WorkflowLoginExample` demonstrates a public variable (`email`), a secret
variable (`password`), a required `IPage` input (so the same definition could run against any open
page), a conditionally skipped step (`check-remember`, guarded by a supplied `false`), a real
action-backed step with a postcondition (`sign-in`), and printing the structured, secret-masked
result.

## Non-goals

Loops and recursion, arbitrary branching graphs, parallel execution, hidden retries, an expression
language, AI/LLM/MCP integration, and treating the browser crawler as a generic workflow step are
all explicitly out of scope for this phase - see [Limitations](#limitations) for the complete list.

## Compatibility

Phase 0.8 adds new API in `webagent4j-workflow` only; no existing Phase 0.1-0.7 public contract
(`webagent4j-action`'s `IActionPlan`/`IPreparedAction` included) was changed to make this module
convenient.

# Actions

WebAgent4J models each browser interaction as one explicit command. Building an action has no side
effect; `execute()` runs a bounded pipeline and returns an immutable `ActionResult<R>`.

```java
ActionResult<Void> result = page.action()
        .click(page.find().button().named("Add to cart").reference())
        .expect(textVisible("1 item"))
        .timeout(Duration.ofSeconds(5))
        .execute();
```

## Lifecycle

Every action follows the same sequence:

```text
semantic target resolution -> preconditions -> backend execution -> stabilization
                           -> observation capture -> postcondition polling -> ActionResult
```

Targets supplied as `IElementReference` values are resolved immediately before execution. A
reference requires a unique semantic match and can be resolved again while the target is still
missing or detached. Ambiguity is never resolved by silently choosing the first candidate.

Implicit preconditions protect target actions. Depending on the operation, the target must be
present, visible, enabled, editable, or interactable. Add domain-specific preconditions with
`precondition(...)` or deterministic verification preconditions with `require(...)`.

Postconditions supplied through `expect(...)` are polled until they succeed or the remaining action
timeout expires. A mismatch is structured result data, not an opaque browser exception.

## Supported actions

- Click and double click
- Plain and secret typing, clearing, and form submission
- Select by value, label, or index
- Check and uncheck
- Focus, blur, hover, and target/page scrolling
- Portable keyboard input
- Navigate, reload, back, and forward
- File upload and download
- Explicit bounded wait

The action builder is single-use and bound to its page. Browser, page, and downloaded-file resources
remain owned by the caller.

## Timeouts and retries

`timeout(...)` is the overall budget for resolution, execution bookkeeping, and verification.
`retry(...)` configures bounded target-resolution attempts - but only a demonstrated, typed
`NOT_FOUND` outcome is actually retried (a resolved-but-detached element counts as `NOT_FOUND`
too); an ambiguous target or a genuine backend/runtime failure ends resolution on the very first
attempt, never retried. Backend execution itself is not blindly retried either: a click, submit,
navigation, or download may produce an irreversible side effect, so it executes at most once, and
only after an explicit check that the action's budget has not already expired - if resolution and
preconditions alone consumed it, the backend action never runs at all. Verification polling may
continue after that single execution.

This distinction is intentional: retrying observation is safe, retrying a purchase is not. Put
precisely, WebAgent4J will not start a backend side effect after its action budget has expired, and
it will never retry the backend side effect as part of wait/poll logic - that is a narrower, and
true, claim than "every action finishes before its timeout", which is not guaranteed: a backend
call already in flight when the deadline passes can still take longer to return.

`timeout(...)` is one shared monotonic budget, not a per-condition allowance: target resolution,
stabilization, and every postcondition draw down the same shrinking deadline in sequence, so a list
of postconditions can never silently add up to several times the configured timeout. See
[wait-and-stability.md](wait-and-stability.md) for the shared polling primitive behind this and
behind locator resolution.

## Observations and semantic diff

Use `captureObservations(ObservationCapturePolicy.ALWAYS)` to retain immutable observations before
and after execution. When both are present, `ActionResult.diff()` contains their semantic diff.
`WHEN_REQUIRED` is the conservative default and `NONE` disables capture. Captures use the same
bounded and redacted observation API described in [Semantic observations](observation.md).

## Uploads and downloads

Uploads accept validated regular `Path` values. Downloads return `DownloadedFile` metadata and use an
explicit destination. Collision behavior is one of `FAIL`, `RENAME`, or `REPLACE`; the default is
safe renaming. Destination paths are normalized and the adapter prevents a suggested filename from
escaping the requested directory.

## Secrets

Use `Secret.of(...)` and `typeSecret(...)` for passwords, tokens, and similar input. `Secret.toString()`
is always redacted. Action events, failures, diagnostics, observations, and result rendering must not
contain the original value. Avoid placing secrets in locator names, URLs, filenames, or custom
predicate messages.

## Failure semantics

Expected failures return `ActionResult` with a stable `ActionStatus` and `ActionFailureType`:
resolution not found or ambiguous, failed precondition, backend execution, failed verification,
timeout, cancellation, unsupported operation, or unsafe retry. `throwIfFailed()` is available when an
exception-oriented calling style is preferred.

The result also exposes phase timings, precondition and postcondition results, audit events, optional
observations and diff, and safe diagnostics. An interrupted action restores the Java thread's
interrupted status. Action builders and live pages are not thread-safe; immutable results,
observations, and verification definitions may be shared.

A target-resolution failure is classified through the typed `ILocatorFailure` contract, never by
exception class name or message text, and the classification looks through a bounded chain of wrapped
causes. Only a failure that is itself, or wraps, a typed "not found" outcome becomes
`TARGET_NOT_FOUND`; only a typed "ambiguous" outcome becomes `TARGET_AMBIGUOUS`. Anything else -
including a real backend or runtime failure such as a browser crash or a disconnected backend - is
`BACKEND_FAILURE`. A backend error is never silently reported as a missing target.

## Execution mode and semantics

`ActionResult.executionMode()` is a required, non-null `ActionExecutionMode`:

- `REAL` - the backend was genuinely invoked, exactly once. This holds even when the backend call
  itself threw, since the invocation happened and a side effect may already exist; combine `REAL`
  with `success()` to know whether that side effect is known to have completed. `executed()` returns
  `true` for `REAL` and never for the other two modes.
- `DRY_RUN` - target resolution and preconditions were validated, but the backend was never invoked.
  `dryRun()` returns `true` only for this mode.
- `NOT_EXECUTED` - the pipeline stopped before the backend stage: resolution failed, the target was
  ambiguous, or a precondition failed.

The legacy `ActionResult(boolean, ...)` constructor cannot observe the true execution mode, so it
always reports `REAL` regardless of whether `success` is `true` or `false` - this is the fail-safe
choice, since `executed() == true` signals "already attempted, do not blindly retry", and an
unattempted failure wrongly marked `REAL` is far less dangerous than an attempted failure wrongly
marked `NOT_EXECUTED`. It is deprecated in favor of the canonical constructor or the explicit
`ActionExecutionMode` overload.

## Dry-run

`dryRun()` runs the exact same target resolution and precondition evaluation as `execute()`, then
returns immediately without invoking the backend:

```java
ActionResult<Void> result = page.action()
        .click(page.find().button().named("Confirm").reference())
        .dryRun()
        .execute();

assert result.dryRun();
assert !result.executed();
```

A dry-run never emits `BACKEND_ACTION_STARTED` or `BACKEND_ACTION_COMPLETED`, never runs
stabilization or postcondition polling (since both depend on a real side effect having happened), and
emits exactly one terminal `ACTION_COMPLETED` event with result `dry-run-validated`. A precondition
that was already going to fail still fails before dry-run is even considered - `dryRun()` only skips
the backend stage, never preconditions.

## Plans

`plan()` goes one step further than `dryRun()`: instead of returning an `ActionResult`, it returns an
immutable, backend-neutral, side-effect-free `IActionPlan<R>` that can be inspected before deciding
whether to execute:

```java
IActionPlan<Void> plan = page.action()
        .click(page.find().button().named("Confirm").reference())
        .expect(Verifications.urlContains("/done"))
        .plan();

if (plan.ready()) {
    ActionResult<Void> result = plan.execute();
}
```

`plan()` runs the same resolution and precondition pipeline as `dryRun()` and `execute()` - it never
invokes the backend - and produces `ActionPlanStatus.READY` only when the target resolved
unambiguously and every precondition held at that moment; otherwise `BLOCKED`, with a structured
`ActionFailure` reusing the same `ActionFailureType` values as `ActionResult`. `IActionPlan` is
obtained only through `plan()`: its sole implementation is package-private, so there is no public
constructor to build a plan by hand or bypass its execution guard.

A plan is a snapshot, not a guarantee. `IActionPlan.execute()` never trusts it: it reruns the entire
pipeline from scratch, so target resolution, ambiguity detection, and preconditions are all
revalidated against the live DOM before any backend side effect. Consequences:

- If the same semantic target survives (even as a different DOM node with the same role and
  accessible name), `execute()` succeeds and the backend runs exactly once.
- If the target is gone, or a semantically different element would now be the only match, `execute()`
  fails `TARGET_NOT_FOUND` and the backend is never invoked on the wrong element.
- If a new ambiguous duplicate appeared since `plan()`, `execute()` fails `TARGET_AMBIGUOUS`.
- If a precondition that held at `plan()` time no longer holds, `execute()` fails
  `PRECONDITION_FAILED`.
- Conversely, a `BLOCKED` plan can still succeed later if the blocking condition clears before
  `execute()` runs - revalidation looks at current state in both directions, never at the plan()-time
  snapshot.

This revalidation covers a structured locator scope too, not just the final target: see
[Dynamic contextual resolution](locators.md#dynamic-contextual-resolution). A plan built while a
context resolved uniquely can still be blocked, or still succeed against a semantically-equivalent
replacement region, depending on what changed before `execute()` runs.

Executing a plan runs the backend at most once, exactly like a direct `execute()` call - `plan()`
followed by `execute()` never doubles the side effect. `IActionPlan.execute()` may itself be called at
most once per plan instance: planning data is immutable, but the execution lifecycle is single-use and
thread-safe, so a second call - even after the first one failed - throws `IllegalStateException`
instead of risking a second real side effect. Build a new plan with `plan()` to try again.
`plan.actionId()` and `plan.execute().actionId()` are always equal, so a result can be traced back to
the plan that produced it even though `execute()` reruns the pipeline from scratch.

`dryRun()` and `plan()` are mutually exclusive terminal modes on the same prepared action:
`dryRun().execute()` validates and returns without ever producing a plan, and `plan()` always builds a
plan whose `execute()` performs the real action. Calling `plan()` after `dryRun()` on the same prepared
action throws `IllegalStateException` rather than silently picking one interpretation.

See [Verification](verification.md) for the built-in conditions and composition rules.

## Frames

`IFrame` implements the same `action()` entry point `IPage` does, so every action described above -
including `dryRun()`, `plan()`, and postcondition verification - works identically inside a resolved
frame:

```java
IFrame checkout = page.frame().named("checkout").single();
checkout.action()
        .click(checkout.find().button().named("Pay").reference())
        .expect(Verifications.textVisible("Payment complete"))
        .execute()
        .throwIfFailed();
```

`plan()`'s revalidation guarantee extends to the frame boundary itself, not only the target inside
it: `IActionPlan.execute()` re-resolves both the frame's own criterion and the element inside it
fresh against live state before touching the backend. Concretely:

- If the frame is removed before `execute()` runs, it fails `TARGET_NOT_FOUND` and the backend is
  never invoked.
- If the frame is removed and replaced by a new `<iframe>` matching the same semantic criterion,
  `execute()` re-resolves the target inside the *new* document and executes against it - never a
  stale reference to the old one.
- If a second frame matching the same criterion appears before `execute()` runs, it fails
  `TARGET_AMBIGUOUS`, exactly like target or context ambiguity does.

`tryFind()` behaves the same way inside a frame as it does for elements: `page.frame().named(...).
tryFind()` and `frame.find()...tryFind()` both return `Optional.empty()` only for a genuine typed
not-found outcome - whether that is the frame itself or a target inside it - and still raise the
normal exception for ambiguity or a backend failure. See [Frames](locators.md#frames) for frame
resolution semantics.

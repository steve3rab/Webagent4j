# Actions

WebAgent4J models a browser interaction as one explicit bounded command. Building an action has no side effect; `execute()` performs target resolution, preconditions, at-most-once backend invocation, stabilization/observation, and postcondition verification.

```java
ActionResult<Void> result = page.action()
        .click(page.find().button().named("Add to cart").reference())
        .expect(Verifications.textVisible("1 item"))
        .timeout(Duration.ofSeconds(5))
        .execute();
```

## Lifecycle

```text
semantic target resolution
        -> preconditions
        -> backend execution (at most once)
        -> stabilization
        -> optional observations/diff
        -> postcondition polling
        -> ActionResult
```

Target references resolve immediately before the operation. Ambiguity never silently selects the first candidate.

## Supported operations

The built-in action taxonomy includes click/double-click, type/secret type, clear, select, check/uncheck, focus/blur/hover/scroll, submit, navigate/reload/back/forward, upload/download, keyboard input, and explicit wait.

## Overall timeout

`timeout(...)` is one action budget shared by target resolution, stabilization, and action-owned postconditions. The budget is checked before backend invocation: if earlier work consumed it, the side effect is not started.

The timeout is **not** a claim that a backend call already in flight can always be forcibly stopped at the deadline. If invocation has already happened, result execution mode remains `REAL` because a side effect may exist.

Elapsed timings use the action's monotonic clock. Wall-clock time is reserved for absolute audit timestamps.

## Retry boundary

`retry(...)` applies to target resolution only. A retry occurs only for demonstrated typed `NOT_FOUND` according to policy. Ambiguity and opaque backend/runtime failures stop immediately. The actual click/type/submit/navigation/download backend call is never blindly repeated by resolution/stabilization/postcondition polling.

This is the core side-effect invariant: read-only observation may repeat; the side effect does not.

## Outcome matrix

| Status | Execution mode | Failure |
| --- | --- | --- |
| `SUCCESS` | `REAL` or `DRY_RUN` | absent |
| `PRECONDITION_FAILED` | `NOT_EXECUTED` | `PRECONDITION_FAILED` |
| `EXECUTION_FAILED` | `NOT_EXECUTED` | `TARGET_NOT_FOUND`, `TARGET_AMBIGUOUS`, `BACKEND_FAILURE` |
| `EXECUTION_FAILED` | `REAL` | `TARGET_NOT_INTERACTABLE`, `ACTION_NOT_SUPPORTED_BY_TARGET`, `BACKEND_FAILURE`, `UPLOAD_FAILURE`, `DOWNLOAD_FAILURE` |
| `VERIFICATION_FAILED` | `REAL` | `POSTCONDITION_FAILED` |
| `TIMEOUT` | `NOT_EXECUTED` or `REAL` | `TIMEOUT` |
| `CANCELLED` | `NOT_EXECUTED` or `REAL` | `INTERRUPTED` |

`REAL` means the backend was invoked, not that the external side effect is known to have committed successfully. This distinction is intentional and prevents unsafe automatic retries.

## Interruption

If interruption is observed before backend invocation, the action becomes `CANCELLED/NOT_EXECUTED/INTERRUPTED`. Once the backend has been invoked or a side effect may have started, cancellation is `CANCELLED/REAL/INTERRUPTED`. The Java thread interrupt flag is preserved.

## Preconditions

Target actions apply implicit preconditions such as presence/visibility/enabled/editable/interactable as appropriate. Caller conditions can be added explicitly. A failed precondition stops before backend execution.

## Dry run

`dryRun()` resolves the target and evaluates preconditions, then returns without invoking the backend, stabilization, or postconditions that depend on a side effect.

```java
ActionResult<Void> result = page.action()
        .click(page.find().button().named("Confirm").reference())
        .dryRun()
        .execute();
```

A dry run is represented explicitly as `SUCCESS/DRY_RUN`, never as fake `REAL` execution.

## Plans

`plan()` returns an inspectable `IActionPlan` after resolution/preconditions without invoking the backend.

A plan is a snapshot, not authorization to trust stale state. `IActionPlan.execute()` reruns target resolution, ambiguity detection, and preconditions from scratch immediately before any side effect.

A plan can therefore:

- be READY at planning time and fail later if the target disappears, becomes ambiguous, or loses a precondition;
- be BLOCKED at planning time and succeed later if the blocking state genuinely clears;
- follow a semantically valid replacement through a reusable reference;
- refuse a stale structured scope that was physically substituted or became ambiguous.

`IActionPlan.execute()` is single-use and thread-safe for that guard: only the first call is allowed to attempt the pipeline. Every later/concurrent call fails without invoking the backend again.

Interruption during `plan()` yields a BLOCKED plan with `INTERRUPTED`, no backend invocation, and preserved thread interrupt status.

## Observations and diff

Observation capture is policy-controlled. When pre/post observations are both retained, the result may include a semantic diff. These are bounded semantic snapshots, not raw DOM/HAR/screenshot recording.

## Upload/download

Uploads require validated file paths. Downloads use an explicit destination and collision policy (`FAIL`, `RENAME`, or `REPLACE` as configured). The adapter prevents a suggested filename from escaping the requested destination directory.

The framework does not decide whether the file/content is business-authorized or safe to execute.

## Secrets and logs

Use secret-aware APIs for passwords/tokens. `ActionEvent#toString()` is structural and excludes target/result/caller metadata text, but explicit accessors intentionally retain in-process detail. `ActionResult` as a general value can contain observations, diagnostics, values, or causes; its ordinary record rendering is not a universal logging boundary.

See [Security model](security-model.md#safe-diagnostics-and-logging).

## Threading

Prepared action builders and live pages are caller-confined. Immutable action results/definitions are shareable after safe publication. The plan execution guard prevents multiple executions of one plan but does not make the underlying page generally thread-safe.

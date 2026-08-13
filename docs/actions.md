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
`retry(...)` configures bounded target-resolution attempts. Backend execution is not blindly retried:
a click, submit, navigation, or download may produce an irreversible side effect, so it executes at
most once. Verification polling may continue after that single execution.

This distinction is intentional: retrying observation is safe, retrying a purchase is not.

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

See [Verification](verification.md) for the built-in conditions and composition rules.

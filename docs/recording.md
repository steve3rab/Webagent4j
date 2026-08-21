# Recording (Phase 0.9-A)

`webagent4j-recording` captures a `WorkflowResult` into an immutable, versioned, secret-safe
`WorkflowRecording`; encodes and decodes it to a canonical JSON transport form; and offers a pure,
offline structured comparison between a recording and a new execution's `WorkflowResult`. This
document covers the purpose, architecture, what is and is not recorded, the secret-safety boundary,
the JSON schema, replay semantics, and the non-goals of this phase.

## Purpose and flow

```
Workflow execution --> WorkflowResult --> WorkflowRecorder --> WorkflowRecording
                                                                       |
                                                          IWorkflowRecordingCodec.encode
                                                                       |
                                                        (persist/transport externally)
                                                                       |
                                                          IWorkflowRecordingCodec.decode
                                                                       |
        caller performs a new workflow execution --> WorkflowResult   |
                                            \                        /
                                             WorkflowReplayVerifier.verify
                                                        |
                                              WorkflowReplayResult (mismatches)
```

This module never persists a recording itself and never transports it anywhere: encoding a
recording to a `String` and getting that string to storage, a file, or another process is entirely
the caller's responsibility - a deliberately narrow scope matching the rest of this phase.

## Architecture: a recording is data, not a program

A `WorkflowRecording` has no `execute()` method, retains no `IWorkflowActionFactory`,
`IPreparedAction`, `IActionPlan`, `IPage`, `IBrowser`, or any other live/backend reference, and
cannot replay itself. **Replaying a recording means asking `WorkflowReplayVerifier` to compare it
against a caller-supplied `WorkflowResult` from a new, independently performed execution - it never
means deserializing a recording and having this module automatically click, type, submit, or
navigate again.** See [Future live-replay boundary](#future-live-replay-boundary) below.

This is a safe-source-model-first design: `WorkflowRecorder` builds its output directly from the
already-safe fields workflow results themselves expose (`WorkflowStepResult`,
`WorkflowConditionResult`, `WorkflowActionSummary`, `WorkflowFailure`), never from a raw execution
model that would need sanitizing afterward. Secret-safety is therefore structural, not a redaction
pass: the recorder's code simply never calls the one method (`WorkflowResult#output`) that could
return a raw secret-capable value, so a secret cannot appear in a recording because the code path
that could observe one is never exercised.

## What is recorded

For each `WorkflowStepResult`, `WorkflowRecorder` copies exactly:

- `stepId`, `stepType`, `status`
- The guard condition's `outcome` and its already-redacted, already-bounded `description` text (via
  `WorkflowConditionResult`) - never `IWorkflowCondition#describe()` re-invoked directly, which
  would bypass `WorkflowEngine`'s termination-time secret redaction
- The published output variable's *name*, never its value
- A safe categorical action projection (`actionId`, `actionType`, `status`, `executionMode`) via
  `WorkflowActionSummary` - never the underlying `ActionResult`'s raw `value`, observations, or
  cause
- A safe structured failure (`type`, already-redacted `safeMessage`, `stepId`,
  `underlyingTypeName`, `actionFailureType`) via `WorkflowFailure` - never a raw `Throwable`

At the top level, `WorkflowRecorder` copies `workflowId`, `status`, every step (above), and the
overall `WorkflowFailure`, if any.

## What is never recorded

- **`WorkflowInputs`** - a workflow's declared inputs, including every secret input, are never
  captured.
- **Raw output/input values** - `WorkflowRecorder` never calls
  `WorkflowResult#output(WorkflowVariable)` and never reads `WorkflowResult#outputs()`; only an
  output variable's *name* is recorded, never its value, secret or not (see
  [REC-SAFE-001](../webagent4j-recording/src/test/java/io/webagent4j/recording/WorkflowRecorderTest.java)).
- **`ActionResult#value()`** or any other raw action output.
- **A raw `Throwable`** - only `WorkflowFailure`'s already-safe `underlyingTypeName` (a class name,
  never a message or stack trace) is retained.
- **The secret registry** - which values were ever marked secret during an execution is not part of
  a recording; only the already-redacted text `WorkflowEngine` itself produced is copied.
- **`IWorkflowActionFactory`, `IPreparedAction`, `IActionPlan`, or any lambda/browser object** -
  none of these are serializable in any sense this module defines, and none is ever referenced by
  a `WorkflowRecording`.

`ActionType` alone is never used to infer whether an action is "safe" to record or replay - every
action-backed step is recorded identically, categorically, regardless of its `ActionType`.

## Recording validity: a recording represents one fail-fast execution

`WorkflowEngine` (Phase 0.8) is sequential and fail-fast: every step runs in definition order, and
the first `FAILED` step stops execution immediately, with every later step recorded as `NOT_RUN`.
`WorkflowRecording`'s constructor - via the package-private `RecordingInvariants` helper - enforces
the shapes that fact actually guarantees, so a recording that could never come from a real execution
is rejected at construction time, whether built directly, produced by `WorkflowRecorder`, or decoded
by `JsonWorkflowRecordingCodec`:

- Every `stepId` in `steps()` is unique.
- **`COMPLETED`**: every step is `SUCCEEDED` or `SKIPPED` - never `FAILED`, never `NOT_RUN`.
- **`FAILED` before execution** (a declared-input validation failure - `MISSING_REQUIRED_INPUT`,
  `INPUT_TYPE_MISMATCH`, or `UNDECLARED_INPUT` in the current engine): the overall failure carries
  no `stepId`, and every step is `NOT_RUN`. There may be zero steps.
- **`FAILED` during execution**: zero or more `SUCCEEDED`/`SKIPPED` steps, then exactly one
  `FAILED` step, then zero or more `NOT_RUN` steps. The overall failure's `stepId` matches that
  step's `stepId`, and the overall failure's `type` and `actionFailureType` match that step's own
  `failure` - `safeMessage` and `underlyingTypeName` are deliberately *not* required to match, since
  `WorkflowReplayVerifier` (below) already treats those two fields as diagnostic, not semantic.

Two additional per-step shapes are enforced for the same reason: a `SKIPPED` step's condition
outcome is always `false` (never `true` - a `true` outcome always proceeds to execution), and a
`SUCCEEDED` `ACTION` step always carries an `action` summary reporting `ActionStatus.SUCCESS` (the
action pipeline's only path to a successful step outcome). A `FAILED` `ACTION` step's `action` may
legitimately be present or absent depending on where execution failed (an `ACTION_FACTORY_FAILED`
step never reached the backend and has no summary; an `ACTION_FAILED` step's summary reports a
non-success status; a step that failed on output publication *after* a successful action - `NULL_OUTPUT`
or `OUTPUT_TYPE_MISMATCH` - carries a summary reporting `ActionStatus.SUCCESS` even though the step
itself is `FAILED`) - so this module never assumes a `FAILED` `ACTION` step's action summary status
correlates with the step's own outcome.

## Secret-safety boundary

Every field above is either (a) categorical/structural data with no secret-capable content
(`stepId`, `stepType`, `status`, enums, `actionId`) or (b) text `WorkflowEngine` had already
redacted and bounded *before* `WorkflowRecorder` ever sees it (`WorkflowConditionResult.description`,
`WorkflowFailure.safeMessage`). `WorkflowRecorder` performs no redaction of its own and needs none:
it is structurally incapable of observing a raw secret value in the first place. See
`SEC-REC-001`..`SEC-REC-004` and `REC-SAFE-001` in
[the recording test suite](../webagent4j-recording/src/test/java/io/webagent4j/recording/) for the
executable proof, including a real-Playwright end-to-end version in
[`WorkflowRecordingIT`](../webagent4j-integration-tests/src/test/java/io/webagent4j/integration/WorkflowRecordingIT.java).

This is a distinct guarantee from **decoder diagnostic safety** (below): the recorder guarantee is
about what a *trusted* `WorkflowResult` can put into a recording; decoder diagnostic safety is about
what `JsonWorkflowRecordingCodec#decode` echoes back out of *untrusted* external JSON when it
rejects that JSON. `decode` cannot verify that a field like `safeMessage` in someone else's JSON is
actually safe - it simply stores whatever schema-valid text is there as ordinary data, and never
repeats any part of a rejected document into its own error.

## JSON schema V1

`JsonWorkflowRecordingCodec` produces exactly one canonical JSON representation per recording:
fields are written in a fixed order via Jackson's streaming `JsonGenerator` (never relying on
default POJO field ordering, which is not a guaranteed stable contract); there is no pretty-printing
and no trailing newline; every optional field is always present as a key, emitted as `null` when
absent - never sometimes omitted; every enum is written by `Enum#name()`, never by ordinal; every
`Instant` is written via its own ISO-8601 UTC `toString()` and read back via `Instant.parse()`.

```json
{
  "schemaVersion": 1,
  "recordingId": "run-42",
  "capturedAt": "2026-01-01T00:00:00Z",
  "workflow": {
    "workflowId": "login",
    "status": "COMPLETED",
    "steps": [
      {
        "stepId": "sign-in",
        "stepType": "ACTION",
        "status": "SUCCEEDED",
        "condition": null,
        "outputVariableName": null,
        "failure": null,
        "action": {
          "actionId": "1f2e...",
          "actionType": "CLICK",
          "status": "SUCCESS",
          "executionMode": "REAL"
        }
      }
    ]
  },
  "failure": null
}
```

A `condition` object is `{"outcome": <boolean>, "description": <string>}`; a `failure` object is
`{"type": <enum>, "safeMessage": <string>, "stepId": <string|null>, "underlyingTypeName":
<string|null>, "actionFailureType": <enum|null>}`.

### Decoding is strict

`JsonWorkflowRecordingCodec#decode` rejects, always as `RecordingFormatException`, never with a
fallback or best-effort interpretation:

- Malformed JSON
- A duplicate JSON object key, at any nesting level (`JsonParser.Feature.STRICT_DUPLICATE_DETECTION`
  on the shared `JsonFactory`)
- A missing required field, or an unknown field (explicit per-level allow-lists)
- An unsupported `schemaVersion` (no fallback decoding of a future or foreign version) - converted
  with an *exact* representability check (`JsonNode.canConvertToInt()`) before ever calling
  `intValue()`, so a numeric token outside the signed 32-bit range can never silently wrap into an
  accidentally-supported version number (`2^32 + 1`'s low 32 bits equal `1`, but it is rejected, not
  decoded as `V1`)
- An invalid enum value
- A malformed `Instant`
- A value of the wrong JSON type
- An impossible step-result combination or cross-step invariant violation (see [Recording validity](#recording-validity-a-recording-represents-one-fail-fast-execution)
  above and
  [`RecordingInvariants`](../webagent4j-recording/src/main/java/io/webagent4j/recording/RecordingInvariants.java))
- Trailing content after the JSON document

Every `RecordingFormatException` message is a fixed, framework-owned string referencing only a
schema field path (`"wrong JSON type for field: $.workflow.steps[2].status"`) or a fixed literal -
it never echoes an unknown field's own name, an invalid enum's own text, a malformed timestamp's
own text, any other part of the offending value, or any slice of the source input, since the JSON
being decoded is untrusted and may itself carry a value the caller needs kept out of logs.
`RecordingFormatException#getCause()` is always `null`: the raw Jackson parser exception - whose own
message can embed a source snippet - is never attached, and an internal domain-validation message is
never blindly republished either. Both `RecordingFormatException` constructors are package-private:
the type exists for a caller to catch, not construct, since a caller-supplied message could not
honor this guarantee. There is no `activateDefaultTyping`, no polymorphic typing, and no arbitrary
class deserialization anywhere in this codec: JSON is walked as a plain node tree and mapped
field-by-field onto this module's own record types, reusing their compact-constructor invariant
checks (including the cross-step checks in `RecordingInvariants`) rather than duplicating them.

## Replay verification semantics

`WorkflowReplayVerifier#verify(WorkflowRecording, WorkflowResult)` is entirely pure and
synchronous - it invokes no browser, no backend, and no `WorkflowEngine`. It is the caller's
responsibility to have already produced `actual` from a genuine new execution. Verification never
fails fast: every mismatch is collected in one deterministic left-to-right traversal - workflow
identity and status, then step count, then each common step index in declaration order, then any
missing/extra trailing steps (in index order), then the top-level failure - so one `verify` call
reports every difference at once, and calling it twice with the same inputs produces an identically
ordered result.

`WorkflowReplayResult#matches()` is derived (`mismatches().isEmpty()`), never an independently
settable field that could disagree with the mismatch list.

### Compared fields

`workflowId`, overall `status`; per step: `stepId`, `stepType`, `status`, condition *presence* and
*outcome*, output-variable *presence* and *name*, action *presence*, `actionType`, `status`,
`executionMode`; per failure (both top-level and per-step): *presence*, `type`, `stepId`,
`actionFailureType`.

### Ignored fields, and why

| Field | Why it is ignored |
|---|---|
| `RecordingId` | Caller-supplied trace metadata, not part of a workflow's semantic outcome. |
| `capturedAt` | A timestamp of when the recording was made, not of what happened. |
| `ActionId` | A fresh random correlation ID assigned per execution (`ActionId.create()`); two semantically identical executions of the same workflow would always mismatch on this field alone if it were compared, making it useless as a signal and actively harmful as a false positive. |
| A condition's `description` text | Diagnostic prose, not the semantic outcome - the *outcome* boolean is what is compared. |
| `WorkflowFailure.safeMessage` | Diagnostic text that can legitimately differ in incidental detail between two semantically identical failures. |
| The underlying exception's class name (`underlyingTypeName`) | An implementation detail, not part of a workflow's documented failure contract. |

## Versioning policy

`RecordingSchemaVersion` is a closed, numbered enum (`V1` only, in this phase). Decoding an unknown
version throws `RecordingFormatException` rather than guessing at a compatible shape. A future
schema version, if ever added, would be a new enum constant with its own encode/decode path - never
a silent reinterpretation of `V1` data.

## Malformed-data behavior

See [Decoding is strict](#decoding-is-strict) above. There is no lenient mode, no partial recovery,
and no "best effort" decode anywhere in this module.

## Future 0.9-B boundary

Deferred to a later phase, not implemented here: persistence (database or filesystem), a plugin SPI
or `ServiceLoader` discovery mechanism for recordings, and any transport beyond the caller handing
an encoded `String` to whatever storage or channel they choose.

## Future live-replay boundary

Automatically re-driving a browser from a `WorkflowRecording` - deserializing a recording and having
this module click, type, submit, or navigate again - is **not implemented in this phase and is not
implied by anything above**. `IActionPlan`, `IPreparedAction`, and `IWorkflowActionFactory` are
deliberately never serialized (see [What is never recorded](#what-is-never-recorded)) precisely
because none of them is a safe, portable description of "what to do" independent of the caller-owned
`IPage`/`IBrowser` session and the live DOM state a real action pipeline needs; recreating one from
persisted data would require design work this phase does not attempt. If live replay is ever added,
it would be an explicit, clearly-labeled opt-in capability of a later phase - never a hidden default
of `WorkflowReplayVerifier` or `IWorkflowRecordingCodec`.

## Compatibility

`webagent4j-recording`'s public API surface: `RecordingId`, `RecordingSchemaVersion`,
`WorkflowRecording`, `RecordedWorkflowStep`, `RecordedCondition`, `RecordedAction`,
`RecordedFailure`, `WorkflowRecorder`, `IWorkflowRecordingCodec`, `JsonWorkflowRecordingCodec`,
`RecordingFormatException`, `WorkflowReplayVerifier`, `WorkflowReplayResult`,
`WorkflowReplayMismatch`, `WorkflowReplayMismatchType`. No Jackson type is ever exposed by a public
method signature; `jackson-databind` is used only inside `JsonWorkflowRecordingCodec`'s
implementation.

## Non-goals

No live browser replay (see [above](#future-live-replay-boundary)); no action recreation; no
workflow-wide or recording-driven retries; no persistence to a database or filesystem; no YAML,
XML, or protobuf encoding - JSON schema V1 only; no screenshot, DOM, observation, HAR, or video
recording; no plugin SPI or `ServiceLoader` discovery; no AI, MCP, or agent integration.

# Recording (Phase 0.9-A)

`webagent4j-recording` captures a `WorkflowResult` into an immutable, versioned recording that
excludes raw workflow values and preserves engine-redacted diagnostics; encodes and decodes it to a
canonical JSON transport form; and offers a pure, offline structured comparison between a recording
and a new execution's `WorkflowResult`. This document covers the purpose, architecture, what is and
is not recorded, the secret-safety and metadata trust boundaries, the JSON schema, replay semantics,
and the non-goals of this phase.

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

This is a restricted-source-model-first design: `WorkflowRecorder` builds its output from
`WorkflowStepResult`, `WorkflowConditionResult`, `WorkflowActionSummary`, and `WorkflowFailure`,
never from raw workflow inputs, outputs, action values, or exceptions. Secret safety is structural
for those raw workflow value channels, not a redaction pass inside Recording. Diagnostic text arrives
already redacted by `WorkflowEngine`. Identifiers supplied by callers, workflow definitions, or
action implementations are a separate metadata trust boundary and are persisted verbatim.

## What is recorded

For each `WorkflowStepResult`, `WorkflowRecorder` copies exactly:

- `stepId`, `stepType`, `status`
- The guard condition's `outcome` and its already-redacted, already-bounded `description` text (via
  `WorkflowConditionResult`) - never `IWorkflowCondition#describe()` re-invoked directly, which
  would bypass `WorkflowEngine`'s termination-time secret redaction
- The published output variable's *name*, never its value
- The action pipeline's `actionId` correlation metadata plus categorical `actionType`, `status`, and
  `executionMode` via `WorkflowActionSummary` - never the underlying `ActionResult`'s raw `value`,
  observations, diagnostics, or cause. `actionId` is persisted verbatim and must be non-sensitive.
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

The live `WorkflowResult`, `WorkflowStepResult`, and `WorkflowFailure` constructors enforce the
same engine-reachable invariants listed below. Recording remains strict when data arrives from JSON,
but it is no longer the first module to discover an impossible live workflow shape. This alignment
does not change schema V1 or replay comparison semantics.

`WorkflowEngine` (Phase 0.8) is sequential and fail-fast: every step runs in definition order, and
the first `FAILED` step stops execution immediately, with every later step recorded as `NOT_RUN`.
`WorkflowRecording`'s constructor - via the package-private `RecordingInvariants` helper - enforces
the shapes that fact actually guarantees, so a recording that could never come from a real execution
is rejected at construction time, whether built directly, produced by `WorkflowRecorder`, or decoded
by `JsonWorkflowRecordingCodec`:

- A recording contains at least one step; schema V1 documents with an empty `steps` array are
  invalid for both `COMPLETED` and preflight `FAILED` outcomes.
- Every `stepId` in `steps()` is unique.
- **`COMPLETED`**: every step is `SUCCEEDED` or `SKIPPED` - never `FAILED`, never `NOT_RUN`.
- **`FAILED` before execution (preflight)**: raised by `WorkflowEngine.Session#validateAndSeedInputs`
  before step 0 ever runs, so it can *only* be one of the three preflight failure types -
  `MISSING_REQUIRED_INPUT`, `INPUT_TYPE_MISMATCH`, `UNDECLARED_INPUT` - and *only* one of those three
  may ever omit a `stepId`. Concretely: the overall failure carries no `stepId`, no
  `underlyingTypeName`, and no `actionFailureType`, and every step is `NOT_RUN`. Every other failure
  type is a **runtime** failure and is rejected unless it carries the
  failing step's `stepId` - a runtime failure type with no `stepId` is invalid even if every step
  happens to be `NOT_RUN`, since the *type* alone determines which shape is legal, not how the steps
  happen to look.
- **`FAILED` during execution (runtime)**: zero or more `SUCCEEDED`/`SKIPPED` steps, then exactly one
  `FAILED` step, then zero or more `NOT_RUN` steps. The overall failure's `stepId` matches that
  step's `stepId`, and - because `WorkflowEngine.Session#run` assigns the exact same `WorkflowFailure`
  instance to both the terminal `WorkflowResult` and the failing step's own `WorkflowStepResult` (see
  `Session#failedResult`), and `WorkflowRecorder` projects both from that one source - the overall
  failure and the FAILED step's own failure must be **fully identical**: `type`, `safeMessage`,
  `stepId`, `underlyingTypeName`, and `actionFailureType` all match, not merely `type` and
  `actionFailureType`. See [Full equality vs. replay semantics](#full-equality-vs-replay-semantics)
  below for why this is a different, non-contradictory rule from what `WorkflowReplayVerifier`
  compares across two separate executions.

A FAILED step's own `failure.stepId` always equals that step's own `stepId` - `RecordedWorkflowStep`
rejects a `FAILED` step whose failure names a different (or absent) step. A `SKIPPED` step's
condition outcome is always `false` (never `true` - a `true` outcome always proceeds to execution). A
`SUCCEEDED` `ASSIGN` step always carries a published `outputVariableName` (`AssignWorkflowStep`
always declares and successfully publishes one), and a `SUCCEEDED` `ACTION` step always carries an
`action` summary reporting `ActionStatus.SUCCESS` (the action pipeline's only path to a successful
step outcome).

### The failure-type / step-type / action-summary matrix

Which step type a runtime failure type can occur on, and what action-summary shape it carries, is
fixed by exactly one path through `ActionWorkflowStep#run` and `WorkflowEngine.Session#executeStep`
per type - not by convention. `RecordedWorkflowStep` enforces exactly this table, derived from that
source, for every `FAILED` step:

| Failure type | Step type | Action summary | Summary's `ActionStatus` |
|---|---|---|---|
| `CONDITION_EVALUATION_FAILED` | `ACTION` or `ASSIGN` | absent | n/a |
| `MISSING_VARIABLE` | `ACTION` only | absent | n/a |
| `ACTION_FACTORY_FAILED` | `ACTION` only | absent | n/a |
| `STEP_EXCEPTION` | `ACTION` only | absent | n/a |
| `ACTION_FAILED` | `ACTION` only | present | Exact action status/mode/failure matrix from [Actions](actions.md#execution-mode-and-semantics) |
| `NULL_OUTPUT` | `ACTION` only | present | `SUCCESS` |
| `OUTPUT_TYPE_MISMATCH` | `ACTION` only | present | `SUCCESS` |

`CONDITION_EVALUATION_FAILED` is the *only* runtime failure type an `ASSIGN` step can carry -
`AssignWorkflowStep#run` is an unconditional `StepRunOutcome.success(...)` with no failure path of
its own, so every other runtime type is impossible on `ASSIGN` today (a structural fact today's
`sealed IWorkflowStep permits AWorkflowStep` / `sealed AWorkflowStep permits ActionWorkflowStep,
AssignWorkflowStep` closed hierarchy makes provable, not merely conventional). `NULL_OUTPUT` and
`OUTPUT_TYPE_MISMATCH` report `ActionStatus.SUCCESS` because both are raised only *after* the action
itself already succeeded, while validating the declared output; `ACTION_FAILED`'s summary is built
from the same non-success `ActionResult` that caused the failure, so its status, execution mode, and
present `ActionFailureType` must preserve the exact action matrix. A merely non-success status is
insufficient. The constructor and strict JSON decoder reject contradictory projections directly.

### Full equality vs. replay semantics

Two rules about `safeMessage` and `underlyingTypeName` sound alike but are not the same axis, and are
not in tension:

- **Within one recording**, the overall failure and the FAILED step's own failure must be fully
  identical, `safeMessage` and `underlyingTypeName` included - because they are, in a genuine
  recording, projections of the literal same `WorkflowFailure` object from one execution. A
  difference in either field within a single recording is not a legitimate variation; it is a sign
  the recording could not have come from a real execution.
- **Across two different recordings/executions**, `WorkflowReplayVerifier` deliberately ignores those
  same two fields (see [Ignored fields, and why](#ignored-fields-and-why) below) - a diagnostic
  message can legitimately differ in incidental detail (an embedded timestamp, a byte offset) between
  two semantically identical executions, and the underlying exception's class name is an
  implementation detail, not part of a workflow's documented failure contract.

The first rule is about internal consistency of one recorded fact; the second is about which parts of
that fact are semantically significant when comparing two independent facts.

## Secret-safety boundary

### Workflow data controlled by Recording

`WorkflowRecorder` never captures:

- `WorkflowInputs`;
- raw `WorkflowOutputs` values or `WorkflowResult#output(...)` results;
- `ActionResult#value()`, observations, or diagnostics;
- raw `Throwable` data; or
- the workflow secret registry.

`WorkflowConditionResult.description` and `WorkflowFailure.safeMessage` arrive already redacted and
bounded by `WorkflowEngine`. Recording preserves that engine-produced text without reaching back
into the raw workflow data channels. See `SEC-REC-001`..`SEC-REC-004` and `REC-SAFE-001` in
[the recording test suite](../webagent4j-recording/src/test/java/io/webagent4j/recording/) for the
executable proof, including a real-Playwright end-to-end version in
[`WorkflowRecordingIT`](../webagent4j-integration-tests/src/test/java/io/webagent4j/integration/WorkflowRecordingIT.java).

### Caller/action-supplied metadata

Recording does not inspect, classify, or redact arbitrary identifier text. `RecordingId` and
`ActionId` are persisted verbatim. Other names retained from framework objects - `WorkflowId`,
`WorkflowStepId`, `outputVariableName`, and `underlyingTypeName` - are also not automatically
secret-redacted by this module. These fields must contain only non-sensitive identifiers or type
metadata.

`ActionId.create()` is the recommended normal source of opaque action correlation identifiers. The
public `ActionId(String)` constructor remains supported for restored or custom identifiers, and a
custom action implementation is responsible for keeping such values non-sensitive. Similarly,
`RecordingId` is caller-owned metadata: Recording cannot protect a caller that deliberately puts a
secret in `new RecordingId(secret)`.

Safe normal metadata includes `ActionId.create()` and `new RecordingId("run-42")`. Passing a
password to `new ActionId(password)` or an API token to `new RecordingId(apiToken)` is misuse of a
metadata field. Recording intentionally performs no heuristic secret detection. Because record
`toString()` output includes metadata fields, sensitive metadata could also appear in JSON,
`toString()`, or application logs. `META-TRUST-001`..`META-TRUST-004` document this boundary with
genuine workflow executions.

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
| `RecordingId` | Caller-supplied trace metadata persisted verbatim, not part of a workflow's semantic outcome. |
| `capturedAt` | A timestamp of when the recording was made, not of what happened. |
| `ActionId` | Correlation metadata persisted verbatim. Normal pipelines use `ActionId.create()`, while custom actions may supply another non-sensitive identifier. In either case identity is not a semantic workflow outcome and is ignored to avoid false mismatches. |
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

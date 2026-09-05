# Recording

`webagent4j-recording` turns one workflow execution into immutable versioned data and encodes/decodes it as canonical JSON. Two schema versions coexist:

- **Recording V1** (`WorkflowRecording`) captures a flat, already-selected step sequence from a `WorkflowResult`, and offers pure structural comparison against a caller-supplied later `WorkflowResult` (`WorkflowReplayVerifier`).
- **Recording V2** (`WorkflowRecordingV2`) additionally captures the execution's structural plan and which branch each conditional actually selected, and can be Deterministically Replayed - see [Recording V2](#recording-v2) and [Deterministic Replay](#deterministic-replay) below.

A recording is **data, not a program**. It cannot execute itself and never automatically re-drives a browser. There is no implicit or automatic conversion between V1 and V2 anywhere in this module.

## V1 flow

```text
WorkflowResult -> WorkflowRecorder -> WorkflowRecording -> JSON
                                                    |
                                   caller stores/transports externally
                                                    |
JSON -> strict decoder -> WorkflowRecording
                                                    |
new caller-run WorkflowResult -> WorkflowReplayVerifier -> mismatch list
```

The recording module provides no filesystem/database transport of its own.

## Captured data

This section, and the two after it, describe Recording V1; [Recording V2](#recording-v2) documents that format's own captured-data shape (a tree instead of a flat list, and a typed `WorkflowPlanOutput` instead of a bare output variable name), which otherwise follows the identical secret-safety and metadata-trust discipline described here.

For each workflow step, recording retains structural identifiers/status, optional condition outcome/safe description, output variable name (not value), action correlation/type/status/execution mode where applicable, and safe structured failure fields.

Top level retains recording ID/capture time, workflow identity/status/steps, and optional overall failure.

## Data structurally excluded

Recording (both V1 and V2) never captures:

- `WorkflowInputs`;
- raw workflow output values;
- `ActionResult.value()`;
- raw action observations/diagnostics;
- raw `Throwable` object/message/stack trace;
- live browser/page/action/factory/plan objects;
- the workflow secret registry.

This is structural exclusion, not a best-effort regex scrub of arbitrary object text.

## Metadata trust boundary

Identifiers such as `RecordingId`, `ActionId`, workflow/step IDs, output-variable names, and type metadata that are part of the schema are not heuristic secret channels. Caller/custom-action metadata is persisted verbatim where the schema says so and may appear in JSON or ordinary record rendering.

Keep those values non-sensitive. Recording cannot protect a caller that deliberately uses a password/token as an ID.

## Valid workflow trace

Schema/model invariants mirror reachable Workflow execution:

- at least one step;
- completed trace only SUCCEEDED/SKIPPED;
- preflight failure: permitted input-failure type, no step ID, every step NOT_RUN;
- runtime failure: exactly one FAILED step between successful/skipped predecessors and NOT_RUN successors;
- failed step's failure identifies that step and matches the overall structural failure;
- succeeded ASSIGN/ACTION and failed ACTION projections use only engine-reachable output/action shapes;
- `ACTION_FAILED` projection follows the exact action result matrix.

Impossible shapes fail at construction/decode rather than being accepted as “old recordings”.

## Canonical JSON V1

Encoding has fixed field order, explicit null optional fields, enum names (never ordinal), ISO-8601 instant strings, no pretty-print/trailing content, and strict duplicate detection on decode.

Decoding rejects malformed JSON, duplicate/unknown/missing fields, wrong JSON types, malformed enum/instant values, unsupported schema versions, invariant violations, and trailing content. Decoder error text should identify a schema path/category without echoing arbitrary rejected raw values.

The machine-readable schema is [schema/recording-v1.schema.json](schema/recording-v1.schema.json). The Java model/codec invariants remain authoritative for cross-field rules that JSON Schema cannot express precisely.

## Decoding resource bounds

`JsonWorkflowRecordingCodec` treats `decode(String)` input as untrusted: acceptance never depends solely on available JVM heap or Jackson's own implementation defaults. Total document size, JSON nesting depth, string/field-name/numeric-token length, and step count are each checked against a deterministic, framework-owned limit strictly before the allocation that limit protects - never after building the full JSON tree or a step-sized collection first. A recording that exceeds a limit fails the same way any other malformed recording does: a safe `RecordingFormatException` that never echoes the rejected content. The exact numeric values are an internal implementation detail, not a published compatibility contract; they are chosen generously enough that no recording a supported encoder legitimately produces is affected.

`encode(WorkflowRecording)` enforces the same step-count, string-length, and total-size limits before returning, so `decode(encode(recording))` never fails on this codec's own resource bounds: a recording too large to decode back is rejected by `encode` itself (`IllegalArgumentException`, with the same never-echo diagnostic discipline as decode) rather than silently accepted and handed to a caller as JSON this codec cannot read back. This guarantee belongs to `JsonWorkflowRecordingCodec` specifically, not to `IWorkflowRecordingCodec` in general.

## Replay comparison

`WorkflowReplayVerifier` performs pure synchronous structural comparison. It never opens a browser, calls an action, or performs I/O.

It compares semantic workflow structure/status/steps/failure categories according to the documented verifier contract and collects mismatches deterministically instead of failing at the first mismatch.

Fields such as capture/recording correlation metadata and diagnostic prose can be deliberately ignored when they are non-semantic across independent executions. That does not weaken the requirement that a **single** recording is internally self-consistent.

## Recording V2

`WorkflowRecordingV2` captures a tree-shaped execution instead of V1's flat list, so a branching execution's actual decision path is explicit rather than merely reconstructible from context:

```text
Workflow -> WorkflowPlanner.plan(workflow) -> WorkflowExecutionPlan
Workflow, WorkflowInputs -> WorkflowEngine#executeWithTree -> WorkflowExecution
WorkflowExecutionPlan, WorkflowExecution -> WorkflowRecorderV2 -> WorkflowRecordingV2 -> JSON
                                                                                |
                                                       caller stores/transports externally
                                                                                |
JSON -> JsonWorkflowRecordingV2Codec (strict decode) -> WorkflowRecordingV2
                                                                                |
                       live Workflow -> ReplayValidator -> ReplayValidationFailure | (compatible)
                                                                                |
                                                             WorkflowReplayer -> IReplayOutcome
```

`recordingId`/`capturedAt`/`workflowId`/`status` mean the same thing as in V1. Structurally, a `WorkflowRecordingV2` additionally carries:

- **`plan`** - the executed workflow's own `WorkflowExecutionPlan` (`WorkflowPlanner.plan(workflow)`), captured once at record time. This is the structural compatibility anchor Deterministic Replay checks against a live workflow's current plan - see [Deterministic Replay](#deterministic-replay) - so V2 needs no separate captured list of declared inputs/outputs to answer "is this still the same workflow."
- **`nodes`** - a tree of `RecordedExecutionNodeV2`, mirroring `WorkflowExecutionTree`: each node carries its own recorded step (`RecordedWorkflowStepV2`) and, for a `CONDITIONAL` step, which branch it selected and that branch's own nodes. A conditional's non-selected branch contributes zero nodes anywhere in the tree, exactly like the live `WorkflowExecutionTree` it mirrors.
- **typed outputs** - `RecordedWorkflowStepV2#output()` is a `WorkflowPlanOutput` (declared name, type, secret classification), not a bare variable name as in V1's `outputVariableName()`. This states not just that a step published something but what kind of value it published - never the value itself.

`RecordedCondition`, `RecordedFailure`, and `RecordedAction` are shared unchanged with V1. `WorkflowRecordingV2` has no relationship to `WorkflowRecording` beyond that: there is no shared version enum (`RecordingSchemaVersionV2` is a disjoint number space from `RecordingSchemaVersion`, so a V1-shaped payload can never be silently accepted as schema `2` or vice versa), no shared root type, and no implicit or automatic V1-to-V2 conversion anywhere in this module. A caller who needs a V2 recording of an already-V1-recorded execution has no supported path other than re-recording the original execution with `WorkflowRecorderV2` - there is no V1→V2 migration API.

`WorkflowRecorderV2#record(recordingId, capturedAt, plan, execution)` requires `plan` and `execution` to describe the same workflow (typically `WorkflowPlanner.plan(workflow)` and `engine.executeWithTree(workflow, inputs)` against the same `workflow` instance); a step that published an output the plan does not declare for it is rejected outright rather than silently recording no output, since silently doing so could misrepresent whether - and under what secret classification - a value was published.

**`nodes` is always a structurally authorized path through `plan` - guaranteed at construction, not just by a well-behaved recorder.** `WorkflowRecordingV2`'s own compact constructor calls `RecordingV2PlanTreeValidator`, which positionally checks - by index, never by an ID lookup elsewhere in the plan - that every recorded step's ID, type, and any published output match the plan node at the same position; that a `CONDITIONAL` step's recorded branch selection is one the plan actually declares for that node (`THEN`/`ELSE`, or `NONE` only where the plan's shape structurally allows it); and that a selected branch's recorded children correspond exclusively to that branch's own plan nodes - no mixing in the non-selected branch, no added or missing node, no reordering. This runs on every construction path - direct Java construction, `WorkflowRecorderV2`, and `JsonWorkflowRecordingV2Codec#decode` alike - so a `WorkflowRecordingV2` whose tree is inconsistent with its own plan can never exist, and Deterministic Replay never has to re-derive this itself (see [Deterministic Replay](#deterministic-replay)).

**A `CONDITIONAL` node's captured decision must be one `WorkflowEngine` can actually produce.** A recorded step's own condition outcome and the enclosing node's branch selection are always captured together or not at all - never a `SUCCEEDED` conditional with no recorded selection, and never a selection recorded without the outcome that produced it - and a `SUCCEEDED` conditional always has both: `WorkflowEngine` only ever finishes evaluating a conditional's branch condition and then immediately determines which branch it selects, so the two are never split. A present outcome always agrees with the selection it implies: `true` only ever selects `THEN`; `false` only ever selects the plan's own non-`THEN` branch (`ELSE` for an `ifElse`, or the structural `NONE` for an `ifThen`, whichever that specific plan node actually declares). A conditional step is never `SKIPPED` - unlike every other step type, it does not support the generic `when(...)` guard, since its one condition slot already carries the mandatory branch-selector meaning.

### Recording V2 resource bounds

`JsonWorkflowRecordingV2Codec` follows the exact same untrusted-input discipline `JsonWorkflowRecordingCodec` documents for V1 (see [Decoding resource bounds](#decoding-resource-bounds)), extended for the tree-plus-plan shape: the execution-node tree and the plan's own node tree are two independently bounded structures, each with its own node-count limit checked before each node is allocated, since a plan always encodes both branches of every conditional while the execution tree encodes only the one actually selected. Nesting depth for both is bounded by a single internal constant (`RecordingV2PlanTreeValidator.MAX_TREE_DEPTH`, currently `64`, the same value `Workflow.MAX_CONDITIONAL_NESTING_DEPTH` enforces for a live workflow definition) - the one source of truth `JsonWorkflowRecordingV2Codec` itself refers to rather than keeping an independent copy. This bound is enforced identically, with a check before every further descent rather than after, at all four points a plan or tree can grow deeper: `WorkflowRecordingV2`'s own construction (so a hand-built or programmatically assembled recording can never exceed it either), `encode` (so it never produces a document `decode` would then have to reject), `decode` (independently, since a recording is untrusted input regardless of source), and Deterministic Replay's own tree traversal (bounded by the same already-enforced construction-time guarantee, so it never needs its own separate check). `decode(encode(recording))` never fails for depth reasons `encode` itself would not have already refused.

There is currently no published machine-readable JSON Schema for Recording V2 (unlike V1's [schema/recording-v1.schema.json](schema/recording-v1.schema.json)); the Java model (`WorkflowRecordingV2`, `RecordedExecutionNodeV2`, `RecordedWorkflowStepV2`) and `JsonWorkflowRecordingV2Codec` are the sole authoritative description of the format.

## Bounded loops

A bounded workflow loop (`WorkflowSteps.loop`, see [Workflows](workflow.md#bounded-loops)) is recorded strictly additively, reusing Recording V2's existing shapes rather than introducing a new schema version: a recorded `WorkflowStepType.LOOP` node's children are its own `WorkflowStepType.LOOP_ITERATION` nodes, one per continuation check the loop actually performed - the recording captures only the iterations that ran, in exact order, never a placeholder for one that never started.

**A `LOOP_ITERATION`'s captured decision must be one `WorkflowEngine` can actually produce.** `RecordingV2PlanTreeValidator` validates it against exactly the states the engine's own loop-iteration logic can produce: no captured outcome at all (evaluation failed, or interruption struck first) with no selection and no children; a `false` outcome, always paired with `WorkflowBranchSelection.NONE` and zero children, never `FAILED`; or a `true` outcome, paired with either `THEN` (the iteration was authorized) or no selection at all, exclusively when the step failed with `LOOP_ITERATION_LIMIT_EXCEEDED` - the bound was reached while still `true`, so that iteration was never authorized to start. `WorkflowBranchSelection.ELSE` is never structurally possible for a loop iteration. Each iteration's own recorded step ID must carry the exact iteration-qualified form `WorkflowEngine` actually produces (composing for a nested loop), checked positionally, never recovered by a fallback ID lookup.

**A `THEN` iteration's children must structurally match its declared body, never merely be non-empty.** When the loop's own recorded body plan is non-empty, a `THEN` selection's children are validated positionally against it exactly like a conditional branch (same step count, IDs, types, outputs, and nested control flow) - a `SUCCEEDED` iteration can never claim `THEN` while recording zero children for a non-empty body: that shape would mean the iteration says it ran the body while the recording shows nothing of it actually happening, which `WorkflowEngine` never produces. Zero children under `THEN` is only ever valid in the two states the engine can genuinely produce it: the declared body is itself empty, or the step failed with `LOOP_STEP_INTERRUPTED` - the executing thread was interrupted after the continuation decision was captured but before the body ever started, so no body steps were ever attempted.

**The declared `maxIterations` bound is deliberately not part of a recording at all.** A `WorkflowExecutionPlan` represents a loop's body once, structurally, and never encodes how many iterations were authorized (see [Workflows](workflow.md#bounded-loops)) - so neither the plan nor `RecordingV2PlanTreeValidator` can check a recorded iteration count against it. That check instead happens only where the live bound is actually available: `ReplayValidator` (see below) rejects a recording whose iteration count for any loop exceeds what the live `Workflow`'s own current definition authorizes for that step, via a new `Workflow#loopMaxIterations(stepId)` accessor - the single piece of a loop's otherwise-internal structure this module reaches across the package boundary for. A hostile recording that is otherwise perfectly self-consistent (a valid plan, a valid tree, valid per-iteration decisions) but claims more iterations than the live loop's declared bound permits is rejected at replay-validation time, not silently accepted because its shape alone looked fine.

## Deterministic Replay

Deterministic Replay (`io.webagent4j.recording.replay`) answers a narrow, explicit question: "does this recorded decision trace still match this live workflow, and if so, what exactly did it decide?" It never answers "what would this workflow do now" - it is not a simulator, and it never takes a new decision.

**This is structural/decision replay, not side-effect replay.** `WorkflowReplayer#replay` never evaluates a condition, never invokes an `IWorkflowActionFactory`, never resolves or verifies a backend target, and never performs any side effect. Real governed-target side-effect replay - re-invoking an action against a freshly re-verified target, with a fresh secret input the recording could never have stored, under this codebase's existing exactly-once governed-execution guarantees entirely unchanged - is a distinct, deliberately not-yet-implemented capability. This 1.3 scope decision follows directly from the fact that a recording is not, and must never become, current authorization for a side effect: any future side-effect-replay capability still has to re-resolve the current target, re-verify its identity, re-evaluate any applicable policy, and re-check for interruption/deadline immediately before its one backend call, exactly as `ActionExecutor` already does for a normal governed action - a recording can shortcut none of that.

A recording is eligible for replay only once **two** independent guarantees both hold - matching the recorded plan is never sufficient on its own, since a plan match alone says nothing about whether the recorded tree paired with it is genuine:

1. **The recording's own internal coherence** - `nodes` is a structurally authorized path through `plan` (see [Recording V2](#recording-v2) above). This is guaranteed unconditionally by `WorkflowRecordingV2`'s own construction, for every construction path, before `ReplayValidator` or `WorkflowReplayer` ever see the recording - so this is a precondition Deterministic Replay relies on, not something it re-derives itself. There is deliberately no `ReplayFailureType` for this case: since an internally inconsistent recording can never be constructed in the first place, replay can never receive one to classify with a replay-time failure.
2. **The recorded plan against the live workflow** - the actual check `ReplayValidator#validate(recording, workflow)` performs:
   - **`INCOMPATIBLE_WORKFLOW`** - `recording.plan()` is not identical to `WorkflowPlanner.plan(workflow)` recomputed fresh from the live definition. Since a plan is deterministic and reads only static step structure, this single equality check catches a step added, removed, retyped, reordered, or a changed branch shape, without needing any separate comparison of declared inputs/outputs.
   - **`UNSUPPORTED_STATUS`** - the recording's status is not `COMPLETED`. Replaying a `FAILED` trace is out of scope for this initial implementation: what "replaying" a failure would mean - especially one that was itself action-related - is a real design question this scope does not attempt to answer rather than guess at.
   - **`LOOP_ITERATION_COUNT_EXCEEDS_BOUND`** - a recorded loop's actual iteration count exceeds the live workflow's own declared `maxIterations` for that step (see [Bounded loops](#bounded-loops) above) - the one loop-specific check this step performs beyond the generic plan-equality comparison.

`WorkflowReplayer#replay` runs that same validation and, if compatible, flattens the recorded execution-node tree into a `ReplayedWorkflow` - an ordered `List<ReplayedStep>`, each pairing a recorded step with the branch decision that led into it if it is `CONDITIONAL`. This flattening loses no information and decides nothing new: it mirrors exactly how `WorkflowEngine` itself builds its own flat `WorkflowResult#steps()` view (a conditional's own decision, immediately followed by whichever single branch it selected, recursively). The non-selected branch of any conditional contributes zero entries to the result, exactly as it contributed zero nodes to the source recording - **the recorded branch decision is the one replayed; a condition is never re-evaluated during replay, and there is no hidden retry, second attempt, or fallback to a different branch or target of any kind.**

Producing an `IReplayOutcome` performs no interruption/deadline check: unlike real action execution, it makes no backend call and produces no side effect to protect, so there is nothing for such a check to guard - its only cost is a bounded, in-memory tree walk. The interruption/deadline discipline `ActionExecutor` and `WorkflowEngine` apply around every real side effect is completely unaffected and remains exactly as strict as ever; a future side-effect-replay capability would need to apply that same discipline around its own new backend calls, not rely on anything from this structural pass.

## Persistence compatibility

Recording JSON V1 and V2 are both stable framework-owned serialized persistence formats, evolving independently: neither's schema version number, field shape, or resource bounds constrain the other's. Native Java serialization of arbitrary WebAgent4J values/exceptions is not a compatibility format for either.

A future incompatible change to either recording shape requires a new schema version (a new constant on `RecordingSchemaVersion` for V1, or a genuinely new enum for a hypothetical V3, following the precedent `RecordingSchemaVersionV2` set of never widening an existing decoder's accepted version space) and an explicit migration/readability policy. Unknown versions must not silently fall back to an earlier one.

## No live side-effect replay

There is intentionally no API that deserializes a recording and reconstructs/executes real browser actions from it. Recording V1 has no replay concept at all beyond pure offline structural comparison (`WorkflowReplayVerifier`); Recording V2's Deterministic Replay (above) is explicit and structural/decision-only, never executing anything. Adding real side-effect replay would require the separate authorization/idempotency/trust design described in [Deterministic Replay](#deterministic-replay) above; it must never appear as an implicit behavior of any recording's decode, comparison, or structural replay.

# Recording

`webagent4j-recording` turns one `WorkflowResult` into immutable versioned data, encodes/decodes canonical JSON schema V1, and compares a recording with a caller-supplied later `WorkflowResult`.

A recording is **data, not a program**. It cannot execute itself and never automatically re-drives a browser.

## Flow

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

For each workflow step, recording retains structural identifiers/status, optional condition outcome/safe description, output variable name (not value), action correlation/type/status/execution mode where applicable, and safe structured failure fields.

Top level retains recording ID/capture time, workflow identity/status/steps, and optional overall failure.

## Data structurally excluded

Recording never captures:

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

## Replay comparison

`WorkflowReplayVerifier` performs pure synchronous structural comparison. It never opens a browser, calls an action, or performs I/O.

It compares semantic workflow structure/status/steps/failure categories according to the documented verifier contract and collects mismatches deterministically instead of failing at the first mismatch.

Fields such as capture/recording correlation metadata and diagnostic prose can be deliberately ignored when they are non-semantic across independent executions. That does not weaken the requirement that a **single** recording is internally self-consistent.

## Persistence compatibility

Recording JSON V1 is the one stable framework-owned serialized persistence format. Native Java serialization of arbitrary WebAgent4J values/exceptions is not a compatibility format.

A future incompatible recording shape requires a new schema version and explicit migration/readability policy. Unknown versions must not silently fall back to V1.

## No live replay

There is intentionally no API that deserializes a recording and reconstructs/executes browser actions. Adding such a feature would require a separate authorization/idempotency/trust design; it must never appear as an implicit behavior of V1 recording decode or comparison.

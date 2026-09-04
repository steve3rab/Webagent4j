package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowId;
import io.webagent4j.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, versioned, tree-shaped recording of one workflow execution - the Recording V2 root
 * type.
 *
 * <p>Unlike {@link WorkflowRecording} (Recording V1), which records a flat, already-selected step
 * sequence, this type also carries the recorded execution's own {@link WorkflowExecutionPlan} - the
 * same deterministic, backend-neutral structural description {@link
 * io.webagent4j.workflow.WorkflowPlanner#plan(io.webagent4j.workflow.Workflow)} produces from a
 * live {@code Workflow} definition, built without ever running it - and a {@link
 * RecordedExecutionNodeV2} tree that preserves which branch each {@code CONDITIONAL} step actually
 * selected, mirroring {@code WorkflowExecutionTree} rather than the flat {@code WorkflowResult}
 * alone. A consumer can check {@code recording.plan().equals(WorkflowPlanner.plan(liveWorkflow))}
 * to decide, without any separate input/output-list bookkeeping, whether a live workflow is
 * structurally the same one this recording was captured from.
 *
 * <p>Like V1, a recording is data, not a program: it has no {@code execute()} method, retains no
 * action factory, prepared action plan, page, or browser reference, and does not replay itself.
 * This type intentionally has no relationship to {@link WorkflowRecording} beyond sharing this
 * module's {@link RecordedFailure}, {@link RecordedCondition}, and {@link RecordedAction}
 * projections and its versioning discipline: there is no implicit or automatic V1-to-V2 conversion
 * anywhere in this module, and nothing here accepts or produces a {@link WorkflowRecording}. See
 * {@code docs/recording.md} for the full architecture, the V1/V2 relationship, and the rationale
 * for what is and is not recorded.
 *
 * <p>This type's own invariants (see {@link RecordingV2Invariants}) validate only that {@link
 * #nodes()} could itself have resulted from one real, sequential, fail-fast execution - the same
 * scope {@link WorkflowRecording}'s invariants already have for V1's flat list - plus that {@link
 * #plan()} and {@link #workflowId()} agree with each other. They deliberately do not cross-check
 * {@link #nodes()} against {@link #plan()}'s own node structure: two recordings built from the same
 * real execution always carry a mutually consistent plan and node tree already, so that check adds
 * nothing for a genuine recording, and a hostile or corrupted one is still bound by every per-node
 * shape check here. Checking a recording's plan and node tree against each other, and both against
 * a live {@code Workflow}'s current structure, is Deterministic Replay's own
 * compatibility-validation responsibility, not this type's.
 *
 * <p>{@code recordingId} and {@code capturedAt} are always caller-supplied, exactly like {@code
 * WorkflowId}: neither is generated inside this module, so the same recording data always produces
 * the same identity and timestamp regardless of how many times it is captured or re-encoded.
 * Identifiers such as {@code recordingId} are persisted verbatim, are visible through record {@code
 * toString()} output, and must contain only non-sensitive metadata.
 *
 * @param schemaVersion the canonical JSON schema version this recording conforms to
 * @param recordingId the non-sensitive caller-supplied recording identifier, persisted verbatim
 * @param capturedAt the caller-supplied capture time
 * @param workflowId the recorded execution's workflow identifier - always equal to {@code
 *     plan.workflowId()}
 * @param status the recorded execution's overall terminal outcome
 * @param plan the recorded execution's own structural plan, used to check compatibility with a live
 *     {@code Workflow} without inspecting its internals
 * @param nodes the top-level recorded execution nodes, in execution order
 * @param failure the overall recorded failure, present exactly when {@code status} is {@link
 *     WorkflowStatus#FAILED}
 * @see RecordingV2Invariants
 */
public record WorkflowRecordingV2(
        RecordingSchemaVersionV2 schemaVersion,
        RecordingId recordingId,
        Instant capturedAt,
        WorkflowId workflowId,
        WorkflowStatus status,
        WorkflowExecutionPlan plan,
        List<RecordedExecutionNodeV2> nodes,
        Optional<RecordedFailure> failure) {

    /** Validates and defensively copies recording data. */
    public WorkflowRecordingV2 {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(recordingId, "recordingId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(plan, "plan");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        failure = Objects.requireNonNull(failure, "failure");
        if (!plan.workflowId().equals(workflowId)) {
            throw new IllegalArgumentException("plan.workflowId() must equal workflowId");
        }
        if (status == WorkflowStatus.FAILED && failure.isEmpty()) {
            throw new IllegalArgumentException("a FAILED recording must carry a failure");
        }
        if (status != WorkflowStatus.FAILED && failure.isPresent()) {
            throw new IllegalArgumentException("only a FAILED recording may carry a failure");
        }
        RecordingV2Invariants.validate(status, nodes, failure);
    }
}

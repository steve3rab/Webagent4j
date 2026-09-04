package io.webagent4j.recording.replay;

import io.webagent4j.recording.WorkflowRecordingV2;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates that a {@link WorkflowRecordingV2} is eligible for Deterministic Replay against a live
 * {@link Workflow}, before any replay of its recorded trace begins.
 *
 * <p>{@code recording} is treated as fully untrusted input; {@code workflow} is treated as trusted,
 * live structure. {@link #validate} invokes nothing on {@code workflow} beyond {@link
 * WorkflowPlanner#plan(Workflow)} - it never evaluates a condition, never invokes an {@code
 * IWorkflowActionFactory}, and never performs any side effect. It is pure and stateless: the same
 * two arguments always produce the same result, and calling it never mutates either argument.
 *
 * <p><b>Workflow identity/compatibility:</b> {@code recording.plan()} (captured once, at record
 * time) is compared for exact equality against {@code WorkflowPlanner.plan(workflow)} (recomputed
 * fresh, from {@code workflow}'s current definition). {@link WorkflowPlanner#plan} is deterministic
 * and reads only a workflow's static step structure, so this equality check is exactly "does {@code
 * workflow}'s current step structure - types, guards, declared outputs, and conditional branch
 * shapes - still match what was recorded," independent of whatever runtime input values a caller
 * might supply. A structural change to the workflow definition since capture (a step added,
 * removed, retyped, or reordered) always fails this check.
 *
 * <p><b>Why only {@code COMPLETED} is supported:</b> replaying a {@code FAILED} recording's trace
 * is out of scope for this initial structural/decision-replay implementation - see {@link
 * ReplayFailureType#UNSUPPORTED_STATUS}. This is a deliberate, documented scope decision, not an
 * oversight: a future revision may define what replaying a failure means, but this one does not
 * guess at it.
 *
 * <p>This validator does not itself replay anything and returns no context for doing so - a
 * separate replay-execution step consumes an already-validated {@code recording}/{@code workflow}
 * pair to reconstruct the recorded decision trace.
 */
public final class ReplayValidator {

    private ReplayValidator() {}

    /**
     * Returns {@link Optional#empty()} if {@code recording} may be replayed against {@code
     * workflow}, or a structured {@link ReplayValidationFailure} explaining why not.
     */
    public static Optional<ReplayValidationFailure> validate(
            WorkflowRecordingV2 recording, Workflow workflow) {
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(workflow, "workflow");
        if (recording.status() != WorkflowStatus.COMPLETED) {
            return Optional.of(
                    new ReplayValidationFailure(
                            ReplayFailureType.UNSUPPORTED_STATUS,
                            "only a COMPLETED recording can be replayed in this scope"));
        }
        if (!recording.plan().equals(WorkflowPlanner.plan(workflow))) {
            return Optional.of(
                    new ReplayValidationFailure(
                            ReplayFailureType.INCOMPATIBLE_WORKFLOW,
                            "recording's plan does not match the live workflow's current"
                                    + " structure"));
        }
        return Optional.empty();
    }
}

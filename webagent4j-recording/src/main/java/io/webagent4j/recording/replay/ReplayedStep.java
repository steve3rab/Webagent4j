package io.webagent4j.recording.replay;

import io.webagent4j.recording.RecordedWorkflowStepV2;
import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowStepType;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry in a {@link ReplayedWorkflow}'s flattened, execution-ordered replay sequence: exactly
 * the recorded step, together with the branch decision that led into it if it is a {@link
 * WorkflowStepType#CONDITIONAL} step.
 *
 * <p>Flattening {@link WorkflowReplayer#replay}'s source recording's execution-node tree into this
 * ordered sequence loses no information a consumer needs to know what actually happened, in what
 * order: a {@code CONDITIONAL} node's own step is followed immediately by whichever single selected
 * branch's own steps, recursively - exactly mirroring how {@code WorkflowEngine} itself builds its
 * flat {@code WorkflowResult#steps()} view (see {@code WorkflowEngine.Session#runSteps}). This
 * sequence carries only what the recording already captured; it is never re-decided, re-ordered, or
 * re-evaluated.
 *
 * @param step the step's own recorded outcome
 * @param branchSelection which branch this step's own decision selected, present only for a {@code
 *     CONDITIONAL} step
 */
public record ReplayedStep(
        RecordedWorkflowStepV2 step, Optional<WorkflowBranchSelection> branchSelection) {

    /** Validates step shape invariants. */
    public ReplayedStep {
        Objects.requireNonNull(step, "step");
        branchSelection = Objects.requireNonNull(branchSelection, "branchSelection");
        if (step.stepType() != WorkflowStepType.CONDITIONAL && branchSelection.isPresent()) {
            throw new IllegalArgumentException(
                    "only a CONDITIONAL step may carry a branch selection");
        }
    }
}

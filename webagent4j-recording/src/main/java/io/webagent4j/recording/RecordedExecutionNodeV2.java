package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowBranchSelection;
import io.webagent4j.workflow.WorkflowStepType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One node of a {@link WorkflowRecordingV2}'s execution tree - the recorded counterpart of {@code
 * WorkflowExecutionNode}, carrying a {@link RecordedWorkflowStepV2} instead of a live {@code
 * WorkflowStepResult}.
 *
 * <p>A conditional's non-selected branch contributes zero nodes anywhere in this tree - never a
 * placeholder, never a speculative entry - exactly mirroring {@code WorkflowExecutionNode}'s own
 * zero-side-effect guarantee.
 *
 * @param step the step's own recorded outcome
 * @param branchSelection which branch this node's step actually selected, present only for a {@link
 *     WorkflowStepType#CONDITIONAL} step whose decision was captured
 * @param children the selected branch's own execution nodes, in execution order - always empty for
 *     a non-{@code CONDITIONAL} step
 */
public record RecordedExecutionNodeV2(
        RecordedWorkflowStepV2 step,
        Optional<WorkflowBranchSelection> branchSelection,
        List<RecordedExecutionNodeV2> children) {

    /**
     * Validates node shape invariants - mirrors {@code WorkflowExecutionNode}'s own rule exactly,
     * including its {@link WorkflowStepType#LOOP}/{@link WorkflowStepType#LOOP_ITERATION}
     * extension: a {@code LOOP} node's children are its own {@code LOOP_ITERATION} nodes and it
     * never itself carries a branch selection; a {@code LOOP_ITERATION} node follows {@code
     * CONDITIONAL}'s own shape (a branch selection whenever it has children).
     */
    public RecordedExecutionNodeV2 {
        Objects.requireNonNull(step, "step");
        branchSelection = Objects.requireNonNull(branchSelection, "branchSelection");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        WorkflowStepType stepType = step.stepType();
        if (stepType == WorkflowStepType.LOOP) {
            if (branchSelection.isPresent()) {
                throw new IllegalArgumentException(
                        "a LOOP execution node never carries a branch selection - see"
                                + " WorkflowStepType#LOOP_ITERATION for each iteration's own"
                                + " decision");
            }
        } else if (stepType == WorkflowStepType.CONDITIONAL
                || stepType == WorkflowStepType.LOOP_ITERATION) {
            if (!children.isEmpty() && branchSelection.isEmpty()) {
                throw new IllegalArgumentException(
                        "a "
                                + stepType
                                + " execution node with children must carry a branch"
                                + " selection");
            }
        } else {
            if (branchSelection.isPresent()) {
                throw new IllegalArgumentException(
                        "only a CONDITIONAL, LOOP, or LOOP_ITERATION step's execution node may"
                                + " carry a branch selection");
            }
            if (!children.isEmpty()) {
                throw new IllegalArgumentException(
                        "only a CONDITIONAL, LOOP, or LOOP_ITERATION step's execution node may"
                                + " have children");
            }
        }
    }
}

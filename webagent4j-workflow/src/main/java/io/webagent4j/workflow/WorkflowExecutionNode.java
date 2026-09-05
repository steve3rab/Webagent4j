package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One node of a {@link WorkflowExecutionTree}: the same immutable {@link WorkflowStepResult}
 * already produced during execution - shared, not recomputed or duplicated (see {@code
 * docs/workflow.md#execution-tree}) - together with, for a {@link WorkflowStepType#CONDITIONAL}
 * step, which branch was actually selected and that branch's own execution nodes, in execution
 * order.
 *
 * <p>This node represents only what actually executed or was explicitly marked {@link
 * WorkflowStepStatus#NOT_RUN} on the path the engine reached. A conditional's non-selected branch
 * contributes zero nodes anywhere in the tree - never a placeholder, never a speculative entry -
 * exactly mirroring the zero-side-effect guarantee the non-selected branch already has at runtime.
 *
 * @param result the step's own already-safe, already-redacted result - see {@link
 *     WorkflowStepResult}'s class-level secret-safety note, which this node inherits unchanged
 * @param branchSelection which branch this node's step actually selected, present only for a {@link
 *     WorkflowStepType#CONDITIONAL} step whose decision was captured (absent if the conditional
 *     failed before a decision existed - see {@code docs/workflow.md#execution-tree})
 * @param children the selected branch's own execution nodes, in execution order - always empty for
 *     a non-{@code CONDITIONAL} step
 */
public record WorkflowExecutionNode(
        WorkflowStepResult result,
        Optional<WorkflowBranchSelection> branchSelection,
        List<WorkflowExecutionNode> children) {

    /**
     * Validates node shape invariants. A {@link WorkflowStepType#LOOP} node's children are its own
     * {@link WorkflowStepType#LOOP_ITERATION} nodes - one per continuation check the loop actually
     * performed, in order - and never itself carries a branch selection, since it represents no
     * single decision; a {@code LOOP_ITERATION} node instead follows exactly {@code CONDITIONAL}'s
     * own shape (a branch selection whenever it has children), since each iteration's continuation
     * check is structurally an {@code ifThen} decision - see {@code
     * WorkflowStepType#LOOP_ITERATION}.
     */
    public WorkflowExecutionNode {
        Objects.requireNonNull(result, "result");
        branchSelection = Objects.requireNonNull(branchSelection, "branchSelection");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        WorkflowStepType stepType = result.stepType();
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

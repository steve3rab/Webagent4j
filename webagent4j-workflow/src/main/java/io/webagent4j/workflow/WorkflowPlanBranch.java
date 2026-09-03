package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;

/**
 * One structurally possible branch of a {@link WorkflowStepType#CONDITIONAL} {@link
 * WorkflowPlanNode} - part of the workflow's static shape, not a runtime decision (see {@code
 * docs/workflow.md#execution-plan} for the distinction from {@link WorkflowExecutionNode}, which
 * represents only the one branch an actual execution selected).
 *
 * @param kind which branch this is: {@link WorkflowBranchSelection#THEN}, {@link
 *     WorkflowBranchSelection#ELSE} (declared {@code elseSteps}), or {@link
 *     WorkflowBranchSelection#NONE} ({@code ifThen}'s structurally absent else - a false decision's
 *     potential no-op outcome, never invented content)
 * @param nodes this branch's own plan nodes, in definition order - always empty for {@link
 *     WorkflowBranchSelection#NONE}, since no steps exist there
 */
public record WorkflowPlanBranch(WorkflowBranchSelection kind, List<WorkflowPlanNode> nodes) {

    /** Validates branch shape invariants. */
    public WorkflowPlanBranch {
        Objects.requireNonNull(kind, "kind");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (kind == WorkflowBranchSelection.NONE && !nodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "a NONE plan branch (ifThen's structurally absent else) can never carry nodes");
        }
    }
}

package io.webagent4j.workflow;

/**
 * Backend-neutral label for one branch of a {@link WorkflowStepType#CONDITIONAL} step, used in two
 * distinct contexts that never mix: as an actual runtime decision - which branch one specific
 * execution selected, see {@link WorkflowExecutionNode#branchSelection()} - or as a purely
 * structural label - which of the two potential branches a {@link WorkflowPlanBranch} represents in
 * a {@link WorkflowExecutionPlan}, before any execution exists and without predicting one (see
 * {@code docs/workflow.md#execution-plan}).
 */
public enum WorkflowBranchSelection {
    /**
     * A runtime execution: the branch condition evaluated {@code true} and {@code thenSteps} was
     * selected. In a plan: the branch that would run if the condition evaluates {@code true} -
     * always structurally present.
     */
    THEN,
    /**
     * A runtime execution: the branch condition evaluated {@code false} and {@code elseSteps} was
     * declared and selected. In a plan: the declared {@code elseSteps} branch, present whenever the
     * conditional step declares one.
     */
    ELSE,
    /**
     * A runtime execution: the branch condition evaluated {@code false} for an {@link
     * WorkflowSteps#ifThen} step, which declares no {@code elseSteps} - a no-op success for the
     * conditional step, never {@code ELSE}. In a plan: the structurally absent else branch of an
     * {@code ifThen} step - never invented content, and a {@link WorkflowPlanBranch} of this kind
     * always carries zero nodes.
     */
    NONE
}

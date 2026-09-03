package io.webagent4j.workflow;

/**
 * Backend-neutral record of which branch a {@link WorkflowStepType#CONDITIONAL} step actually
 * selected during one execution - see {@link WorkflowExecutionNode#branchSelection()}.
 */
public enum WorkflowBranchSelection {
    /** The branch condition evaluated {@code true}; {@code thenSteps} was selected. */
    THEN,
    /** The branch condition evaluated {@code false} and {@code elseSteps} was declared. */
    ELSE,
    /**
     * The branch condition evaluated {@code false} for an {@link WorkflowSteps#ifThen} step, which
     * declares no {@code elseSteps} - a no-op success for the conditional step, never {@code ELSE}.
     */
    NONE
}

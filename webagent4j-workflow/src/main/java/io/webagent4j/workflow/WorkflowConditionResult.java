package io.webagent4j.workflow;

import java.util.Objects;

/**
 * Safe, structured outcome of evaluating one step's {@link IWorkflowCondition}, explaining why a
 * step was executed or {@link WorkflowStepStatus#SKIPPED}.
 *
 * @param outcome whether the condition evaluated to {@code true} or {@code false}
 * @param description the condition's own safe, secret-masked {@link IWorkflowCondition#describe()}
 */
public record WorkflowConditionResult(boolean outcome, String description) {

    /** Validates that {@code description} is non-null. */
    public WorkflowConditionResult {
        Objects.requireNonNull(description, "description");
    }
}

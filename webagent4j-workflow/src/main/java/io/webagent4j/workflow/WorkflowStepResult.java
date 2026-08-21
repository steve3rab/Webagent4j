package io.webagent4j.workflow;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, safe, structured outcome of one {@link IWorkflowStep} within a {@link WorkflowResult}.
 *
 * @param stepId the step's identifier
 * @param stepType the step's broad category
 * @param status the step's terminal status
 * @param condition the guard condition's outcome, if the step had one and it was evaluated
 * @param outputVariableName the name of the variable this step published, if it produced one
 * @param failure the safe structured failure, if {@code status} is {@link
 *     WorkflowStepStatus#FAILED}
 * @param actionSummary a safe projection of the underlying {@code ActionResult}, for an {@link
 *     WorkflowStepType#ACTION} step that reached execution
 */
public record WorkflowStepResult(
        WorkflowStepId stepId,
        WorkflowStepType stepType,
        WorkflowStepStatus status,
        Optional<WorkflowConditionResult> condition,
        Optional<String> outputVariableName,
        Optional<WorkflowFailure> failure,
        Optional<WorkflowActionSummary> actionSummary) {

    /** Validates step result data. */
    public WorkflowStepResult {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(stepType, "stepType");
        Objects.requireNonNull(status, "status");
        condition = Objects.requireNonNull(condition, "condition");
        outputVariableName = Objects.requireNonNull(outputVariableName, "outputVariableName");
        failure = Objects.requireNonNull(failure, "failure");
        actionSummary = Objects.requireNonNull(actionSummary, "actionSummary");
        if (status == WorkflowStepStatus.FAILED && failure.isEmpty()) {
            throw new IllegalArgumentException("a FAILED step result must carry a failure");
        }
        if (status != WorkflowStepStatus.FAILED && failure.isPresent()) {
            throw new IllegalArgumentException("only a FAILED step result may carry a failure");
        }
        if (status == WorkflowStepStatus.SKIPPED) {
            if (condition.isEmpty()) {
                throw new IllegalArgumentException(
                        "a SKIPPED step result must carry a condition outcome");
            }
            if (outputVariableName.isPresent()) {
                throw new IllegalArgumentException(
                        "a SKIPPED step result cannot carry an output variable name");
            }
            if (actionSummary.isPresent()) {
                throw new IllegalArgumentException(
                        "a SKIPPED step result cannot carry an action summary");
            }
        }
        if (status == WorkflowStepStatus.NOT_RUN) {
            if (condition.isPresent()) {
                throw new IllegalArgumentException(
                        "a NOT_RUN step result cannot carry a condition outcome");
            }
            if (outputVariableName.isPresent()) {
                throw new IllegalArgumentException(
                        "a NOT_RUN step result cannot carry an output variable name");
            }
            if (actionSummary.isPresent()) {
                throw new IllegalArgumentException(
                        "a NOT_RUN step result cannot carry an action summary");
            }
        }
    }

    /** Renders only safe, already-structured fields - never a raw value or exception. */
    @Override
    public String toString() {
        return "WorkflowStepResult[step="
                + stepId.value()
                + ", type="
                + stepType
                + ", status="
                + status
                + condition
                        .map(c -> ", condition=" + c.description() + "=" + c.outcome())
                        .orElse("")
                + outputVariableName.map(name -> ", output=" + name).orElse("")
                + failure.map(f -> ", failure=" + f).orElse("")
                + "]";
    }
}

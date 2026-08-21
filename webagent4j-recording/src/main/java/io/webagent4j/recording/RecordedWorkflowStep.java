package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe, recorded outcome of one workflow step.
 *
 * <p>Mirrors {@code WorkflowStepResult} field-for-field, with the same FAILED/SKIPPED/NOT_RUN
 * invariants, plus two invariants specific to a recording: an {@code ASSIGN} step never carries an
 * {@link #action()}, and a step whose condition outcome is {@code false} must be {@link
 * WorkflowStepStatus#SKIPPED}.
 *
 * @param stepId the step's identifier
 * @param stepType the step's broad category
 * @param status the step's terminal status
 * @param condition the guard condition's recorded outcome, if the step had one and it was evaluated
 * @param outputVariableName the name of the variable this step published, if it produced one
 * @param failure the recorded failure, if {@code status} is {@link WorkflowStepStatus#FAILED}
 * @param action a safe recorded projection of the underlying action result, for an {@code ACTION}
 *     step that reached execution
 */
public record RecordedWorkflowStep(
        WorkflowStepId stepId,
        WorkflowStepType stepType,
        WorkflowStepStatus status,
        Optional<RecordedCondition> condition,
        Optional<String> outputVariableName,
        Optional<RecordedFailure> failure,
        Optional<RecordedAction> action) {

    /** Validates step data. */
    public RecordedWorkflowStep {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(stepType, "stepType");
        Objects.requireNonNull(status, "status");
        condition = Objects.requireNonNull(condition, "condition");
        outputVariableName = Objects.requireNonNull(outputVariableName, "outputVariableName");
        failure = Objects.requireNonNull(failure, "failure");
        action = Objects.requireNonNull(action, "action");
        if (status == WorkflowStepStatus.FAILED && failure.isEmpty()) {
            throw new IllegalArgumentException("a FAILED step must carry a failure");
        }
        if (status != WorkflowStepStatus.FAILED && failure.isPresent()) {
            throw new IllegalArgumentException("only a FAILED step may carry a failure");
        }
        if (status == WorkflowStepStatus.SKIPPED) {
            if (condition.isEmpty()) {
                throw new IllegalArgumentException("a SKIPPED step must carry a condition outcome");
            }
            if (outputVariableName.isPresent()) {
                throw new IllegalArgumentException(
                        "a SKIPPED step cannot carry an output variable name");
            }
            if (action.isPresent()) {
                throw new IllegalArgumentException("a SKIPPED step cannot carry an action");
            }
        }
        if (status == WorkflowStepStatus.NOT_RUN) {
            if (condition.isPresent()) {
                throw new IllegalArgumentException(
                        "a NOT_RUN step cannot carry a condition outcome");
            }
            if (outputVariableName.isPresent()) {
                throw new IllegalArgumentException(
                        "a NOT_RUN step cannot carry an output variable name");
            }
            if (action.isPresent()) {
                throw new IllegalArgumentException("a NOT_RUN step cannot carry an action");
            }
        }
        if (condition.isPresent()
                && !condition.get().outcome()
                && status != WorkflowStepStatus.SKIPPED) {
            throw new IllegalArgumentException(
                    "a step with a false condition outcome must be SKIPPED");
        }
        if (stepType == WorkflowStepType.ASSIGN && action.isPresent()) {
            throw new IllegalArgumentException("an ASSIGN step cannot carry an action");
        }
    }
}

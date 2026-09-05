package io.webagent4j.workflow;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, safe, structured outcome of one {@link IWorkflowStep} within a {@link WorkflowResult}.
 *
 * @param stepId the step's identifier
 * @param stepType the step's broad category
 * @param status the step's terminal status
 * @param condition for an {@code ACTION}/{@code ASSIGN} step, its optional guard condition's
 *     outcome, if it had one and it was evaluated - {@code false} always implies {@code SKIPPED}.
 *     For a {@code CONDITIONAL} step, its mandatory branch decision instead - always present unless
 *     the step failed before a decision was captured, and a {@code false} outcome never implies
 *     {@code SKIPPED} for this step type (see {@link WorkflowSteps#ifElse}).
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
        if (status == WorkflowStepStatus.FAILED && outputVariableName.isPresent()) {
            throw new IllegalArgumentException(
                    "a FAILED step result cannot carry a published output variable name");
        }
        if (status == WorkflowStepStatus.FAILED) {
            WorkflowFailure stepFailure = failure.orElseThrow();
            if (stepFailure.stepId().isEmpty() || !stepFailure.stepId().get().equals(stepId)) {
                throw new IllegalArgumentException(
                        "a FAILED step's failure.stepId must equal the step's own stepId");
            }
            requireFailureShapeMatchesStepTypeAndAction(stepType, stepFailure, actionSummary);
        }
        if (stepType == WorkflowStepType.ASSIGN
                && status == WorkflowStepStatus.SUCCEEDED
                && outputVariableName.isEmpty()) {
            throw new IllegalArgumentException(
                    "a SUCCEEDED ASSIGN step must carry a published output variable name");
        }
        if (status == WorkflowStepStatus.SKIPPED) {
            if (condition.isEmpty()) {
                throw new IllegalArgumentException(
                        "a SKIPPED step result must carry a condition outcome");
            }
            if (condition.get().outcome()) {
                throw new IllegalArgumentException(
                        "a SKIPPED step result's condition outcome must be false");
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
        if (stepType != WorkflowStepType.CONDITIONAL
                && stepType != WorkflowStepType.LOOP_ITERATION
                && condition.isPresent()
                && !condition.get().outcome()
                && status != WorkflowStepStatus.SKIPPED) {
            throw new IllegalArgumentException(
                    "a step result with a false condition outcome must be SKIPPED");
        }
        if (stepType == WorkflowStepType.CONDITIONAL) {
            if (outputVariableName.isPresent()) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL step cannot carry a published output variable name");
            }
            if (actionSummary.isPresent()) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL step cannot carry an action summary");
            }
            if (status == WorkflowStepStatus.SKIPPED) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL step's own branch decision is never SKIPPED - see"
                                + " WorkflowSteps#ifElse/ifThen");
            }
        }
        if (stepType == WorkflowStepType.LOOP) {
            if (condition.isPresent()) {
                throw new IllegalArgumentException(
                        "a LOOP step's own result never carries a condition outcome - each"
                                + " iteration's continuation check is its own LOOP_ITERATION result");
            }
            if (outputVariableName.isPresent()) {
                throw new IllegalArgumentException(
                        "a LOOP step cannot carry a published output variable name");
            }
            if (actionSummary.isPresent()) {
                throw new IllegalArgumentException("a LOOP step cannot carry an action summary");
            }
            if (status == WorkflowStepStatus.SKIPPED) {
                throw new IllegalArgumentException(
                        "a LOOP step's own result is never SKIPPED - see WorkflowSteps#loop");
            }
        }
        if (stepType == WorkflowStepType.LOOP_ITERATION) {
            if (outputVariableName.isPresent()) {
                throw new IllegalArgumentException(
                        "a LOOP_ITERATION result cannot carry a published output variable name -"
                                + " an iteration's body steps carry their own outputs");
            }
            if (actionSummary.isPresent()) {
                throw new IllegalArgumentException(
                        "a LOOP_ITERATION result cannot carry an action summary");
            }
            if (status == WorkflowStepStatus.SKIPPED) {
                throw new IllegalArgumentException(
                        "a LOOP_ITERATION's own continuation decision is never SKIPPED - see"
                                + " WorkflowSteps#loop");
            }
        }
        if (stepType == WorkflowStepType.ASSIGN && actionSummary.isPresent()) {
            throw new IllegalArgumentException("an ASSIGN step cannot carry an action summary");
        }
        if (stepType == WorkflowStepType.ACTION && status == WorkflowStepStatus.SUCCEEDED) {
            if (actionSummary.isEmpty()) {
                throw new IllegalArgumentException(
                        "a SUCCEEDED ACTION step must carry an action summary");
            }
            if (actionSummary.get().status() != ActionStatus.SUCCESS) {
                throw new IllegalArgumentException(
                        "a SUCCEEDED ACTION step's action summary must report ActionStatus.SUCCESS");
            }
        }
    }

    private static void requireFailureShapeMatchesStepTypeAndAction(
            WorkflowStepType stepType,
            WorkflowFailure failure,
            Optional<WorkflowActionSummary> actionSummary) {
        WorkflowFailureType failureType = failure.type();
        switch (failureType) {
            case MISSING_REQUIRED_INPUT, INPUT_TYPE_MISMATCH, UNDECLARED_INPUT ->
                    throw new IllegalArgumentException(
                            "a preflight failure type cannot be a step's own failure");
            case CONDITION_EVALUATION_FAILED -> requireNoActionSummary(actionSummary, failureType);
            case MISSING_VARIABLE, ACTION_FACTORY_FAILED, STEP_EXCEPTION -> {
                requireActionStepType(stepType, failureType);
                requireNoActionSummary(actionSummary, failureType);
            }
            case ACTION_FAILED -> {
                requireActionStepType(stepType, failureType);
                WorkflowActionSummary summary =
                        requireActionSummaryWithNonSuccessStatus(actionSummary, failureType);
                requireActionFailureOutcome(
                        summary.status(),
                        summary.executionMode(),
                        failure.actionFailureType().orElseThrow());
            }
            case NULL_OUTPUT, OUTPUT_TYPE_MISMATCH -> {
                requireActionStepType(stepType, failureType);
                requireActionSummaryWithSuccessStatus(actionSummary, failureType);
            }
            case CONDITIONAL_STEP_INTERRUPTED -> {
                if (stepType != WorkflowStepType.CONDITIONAL) {
                    throw new IllegalArgumentException(
                            failureType + " can only occur on a CONDITIONAL step");
                }
                requireNoActionSummary(actionSummary, failureType);
            }
            case LOOP_ITERATION_LIMIT_EXCEEDED, LOOP_STEP_INTERRUPTED -> {
                if (stepType != WorkflowStepType.LOOP_ITERATION) {
                    throw new IllegalArgumentException(
                            failureType + " can only occur on a LOOP_ITERATION step");
                }
                requireNoActionSummary(actionSummary, failureType);
            }
            case EXECUTED_NODE_BUDGET_EXCEEDED ->
                    requireNoActionSummary(actionSummary, failureType);
        }
    }

    private static void requireActionStepType(
            WorkflowStepType stepType, WorkflowFailureType failureType) {
        if (stepType != WorkflowStepType.ACTION) {
            throw new IllegalArgumentException(failureType + " can only occur on an ACTION step");
        }
    }

    private static void requireNoActionSummary(
            Optional<WorkflowActionSummary> actionSummary, WorkflowFailureType failureType) {
        if (actionSummary.isPresent()) {
            throw new IllegalArgumentException(failureType + " cannot carry an action summary");
        }
    }

    private static void requireActionSummaryWithSuccessStatus(
            Optional<WorkflowActionSummary> actionSummary, WorkflowFailureType failureType) {
        if (actionSummary.isEmpty()) {
            throw new IllegalArgumentException(failureType + " must carry an action summary");
        }
        if (actionSummary.get().status() != ActionStatus.SUCCESS) {
            throw new IllegalArgumentException(
                    failureType + "'s action summary must report ActionStatus.SUCCESS");
        }
    }

    private static WorkflowActionSummary requireActionSummaryWithNonSuccessStatus(
            Optional<WorkflowActionSummary> actionSummary, WorkflowFailureType failureType) {
        if (actionSummary.isEmpty()) {
            throw new IllegalArgumentException(failureType + " must carry an action summary");
        }
        if (actionSummary.get().status() == ActionStatus.SUCCESS) {
            throw new IllegalArgumentException(
                    failureType + "'s action summary must not report ActionStatus.SUCCESS");
        }
        return actionSummary.get();
    }

    private static void requireActionFailureOutcome(
            ActionStatus status, ActionExecutionMode executionMode, ActionFailureType failureType) {
        boolean valid =
                switch (status) {
                    case PRECONDITION_FAILED ->
                            executionMode == ActionExecutionMode.NOT_EXECUTED
                                    && failureType == ActionFailureType.PRECONDITION_FAILED;
                    case EXECUTION_FAILED ->
                            switch (executionMode) {
                                case NOT_EXECUTED ->
                                        failureType == ActionFailureType.TARGET_NOT_FOUND
                                                || failureType == ActionFailureType.TARGET_AMBIGUOUS
                                                || failureType == ActionFailureType.BACKEND_FAILURE
                                                || failureType == ActionFailureType.TARGET_CHANGED
                                                || failureType == ActionFailureType.POLICY_DENIED
                                                || failureType
                                                        == ActionFailureType
                                                                .POLICY_EVALUATION_FAILED;
                                case REAL ->
                                        failureType == ActionFailureType.TARGET_NOT_INTERACTABLE
                                                || failureType
                                                        == ActionFailureType
                                                                .ACTION_NOT_SUPPORTED_BY_TARGET
                                                || failureType == ActionFailureType.BACKEND_FAILURE
                                                || failureType == ActionFailureType.UPLOAD_FAILURE
                                                || failureType == ActionFailureType.DOWNLOAD_FAILURE
                                                || failureType == ActionFailureType.POLICY_VIOLATION
                                                || failureType
                                                        == ActionFailureType.STABILIZATION_FAILED;
                                case DRY_RUN -> false;
                            };
                    case VERIFICATION_FAILED ->
                            executionMode == ActionExecutionMode.REAL
                                    && failureType == ActionFailureType.POSTCONDITION_FAILED;
                    case TIMEOUT ->
                            (executionMode == ActionExecutionMode.REAL
                                            || executionMode == ActionExecutionMode.NOT_EXECUTED)
                                    && failureType == ActionFailureType.TIMEOUT;
                    case CANCELLED ->
                            (executionMode == ActionExecutionMode.REAL
                                            || executionMode == ActionExecutionMode.NOT_EXECUTED)
                                    && failureType == ActionFailureType.INTERRUPTED;
                    case SUCCESS -> false;
                };
        if (!valid) {
            throw new IllegalArgumentException(
                    "action status, execution mode, and failure type are inconsistent");
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

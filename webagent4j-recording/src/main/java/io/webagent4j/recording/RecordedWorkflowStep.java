package io.webagent4j.recording;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe, recorded outcome of one workflow step.
 *
 * <p>Mirrors {@code WorkflowStepResult} field-for-field, with the same FAILED/SKIPPED/NOT_RUN
 * invariants, plus invariants specific to a recording, each guaranteed by every path through {@code
 * WorkflowEngine.executeStep}: an {@code ASSIGN} step never carries an {@link #action()}; a step's
 * condition, when present, is {@code SKIPPED} if and only if its outcome is {@code false}; a {@code
 * FAILED} step never carries a published {@link #outputVariableName()} (a variable is only
 * published after a step's run outcome succeeds); a {@code SUCCEEDED} {@code ACTION} step always
 * carries an {@link #action()} whose {@link RecordedAction#status()} is {@code SUCCESS} (the action
 * pipeline's only path to a successful step outcome); a {@code SUCCEEDED} {@code ASSIGN} step
 * always carries a published {@link #outputVariableName()} ({@code AssignWorkflowStep} always
 * declares and successfully publishes one); a FAILED step's own {@link #failure()} always carries a
 * {@code stepId} equal to this step's own {@link #stepId()}; and its {@code failure().type()}
 * constrains exactly which step type and action-summary shape are possible - see {@code
 * requireFailureShapeMatchesStepTypeAndAction} below.
 *
 * @param stepId the step's identifier
 * @param stepType the step's broad category
 * @param status the step's terminal status
 * @param condition mirrors {@code WorkflowStepResult#condition}: a guard outcome for {@code
 *     ACTION}/{@code ASSIGN} ({@code false} always implies {@code SKIPPED}), or a {@code
 *     CONDITIONAL} step's mandatory branch decision ({@code false} never implies {@code SKIPPED})
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
        if (status == WorkflowStepStatus.FAILED && outputVariableName.isPresent()) {
            throw new IllegalArgumentException(
                    "a FAILED step cannot carry a published output variable name");
        }
        if (status == WorkflowStepStatus.FAILED) {
            RecordedFailure stepFailure = failure.get();
            if (stepFailure.stepId().isEmpty() || !stepFailure.stepId().get().equals(stepId)) {
                throw new IllegalArgumentException(
                        "a FAILED step's own failure.stepId must equal the step's own stepId");
            }
            requireFailureShapeMatchesStepTypeAndAction(stepType, stepFailure, action);
        }
        if (stepType == WorkflowStepType.ASSIGN
                && status == WorkflowStepStatus.SUCCEEDED
                && outputVariableName.isEmpty()) {
            throw new IllegalArgumentException(
                    "a SUCCEEDED ASSIGN step must carry a published output variable name");
        }
        if (status == WorkflowStepStatus.SKIPPED) {
            if (condition.isEmpty()) {
                throw new IllegalArgumentException("a SKIPPED step must carry a condition outcome");
            }
            if (condition.get().outcome()) {
                throw new IllegalArgumentException(
                        "a SKIPPED step's condition outcome must be false");
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
        if (stepType != WorkflowStepType.CONDITIONAL
                && condition.isPresent()
                && !condition.get().outcome()
                && status != WorkflowStepStatus.SKIPPED) {
            throw new IllegalArgumentException(
                    "a step with a false condition outcome must be SKIPPED");
        }
        if (stepType == WorkflowStepType.CONDITIONAL) {
            if (outputVariableName.isPresent()) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL step cannot carry a published output variable name");
            }
            if (action.isPresent()) {
                throw new IllegalArgumentException("a CONDITIONAL step cannot carry an action");
            }
            if (status == WorkflowStepStatus.SKIPPED) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL step's own branch decision is never SKIPPED");
            }
        }
        if (stepType == WorkflowStepType.ASSIGN && action.isPresent()) {
            throw new IllegalArgumentException("an ASSIGN step cannot carry an action");
        }
        if (stepType == WorkflowStepType.ACTION && status == WorkflowStepStatus.SUCCEEDED) {
            if (action.isEmpty()) {
                throw new IllegalArgumentException(
                        "a SUCCEEDED ACTION step must carry an action summary");
            }
            if (action.get().status() != ActionStatus.SUCCESS) {
                throw new IllegalArgumentException(
                        "a SUCCEEDED ACTION step's action summary must report ActionStatus.SUCCESS");
            }
        }
    }

    /**
     * Enforces the exact failure-type/step-type/action-summary shape {@code WorkflowEngine} can
     * produce for a FAILED step (see {@code ActionWorkflowStep#run} and {@code
     * WorkflowEngine.Session#executeStep}):
     *
     * <ul>
     *   <li>A preflight-only failure type ({@code MISSING_REQUIRED_INPUT}, {@code
     *       INPUT_TYPE_MISMATCH}, {@code UNDECLARED_INPUT}) can never be a step's own failure - it
     *       only ever occurs before step 0, as the overall failure of an all-{@code NOT_RUN}
     *       recording.
     *   <li>{@code CONDITION_EVALUATION_FAILED} never carries an action summary, on either step
     *       type - it always occurs before {@code AWorkflowStep#run} is ever called.
     *   <li>{@code MISSING_VARIABLE}, {@code ACTION_FACTORY_FAILED}, and {@code STEP_EXCEPTION}
     *       only occur on an {@code ACTION} step ({@code AssignWorkflowStep#run} never throws and
     *       never returns a failed outcome) and never carry an action summary - none of them reach
     *       a successfully-executed {@code ActionResult}.
     *   <li>{@code ACTION_FAILED} only occurs on an {@code ACTION} step and always carries an
     *       action summary reporting a non-success status - {@code ActionWorkflowStep} builds the
     *       summary from the same {@code ActionResult} whose non-success status caused the failure.
     *   <li>{@code NULL_OUTPUT} and {@code OUTPUT_TYPE_MISMATCH} only occur on an {@code ACTION}
     *       step and always carry an action summary reporting {@code ActionStatus.SUCCESS} - both
     *       are raised only after the action itself already succeeded, while validating the
     *       declared output.
     * </ul>
     */
    private static void requireFailureShapeMatchesStepTypeAndAction(
            WorkflowStepType stepType, RecordedFailure failure, Optional<RecordedAction> action) {
        WorkflowFailureType failureType = failure.type();
        switch (failureType) {
            case MISSING_REQUIRED_INPUT, INPUT_TYPE_MISMATCH, UNDECLARED_INPUT ->
                    throw new IllegalArgumentException(
                            "a preflight failure type cannot be a step's own failure");
            case CONDITION_EVALUATION_FAILED -> requireNoActionSummary(action, failureType);
            case MISSING_VARIABLE, ACTION_FACTORY_FAILED, STEP_EXCEPTION -> {
                requireActionStepType(stepType, failureType);
                requireNoActionSummary(action, failureType);
            }
            case ACTION_FAILED -> {
                requireActionStepType(stepType, failureType);
                RecordedAction summary =
                        requireActionSummaryWithNonSuccessStatus(action, failureType);
                requireActionFailureOutcome(
                        summary.status(),
                        summary.executionMode(),
                        failure.actionFailureType().orElseThrow());
            }
            case NULL_OUTPUT, OUTPUT_TYPE_MISMATCH -> {
                requireActionStepType(stepType, failureType);
                requireActionSummaryWithSuccessStatus(action, failureType);
            }
            case CONDITIONAL_STEP_INTERRUPTED -> {
                if (stepType != WorkflowStepType.CONDITIONAL) {
                    throw new IllegalArgumentException(
                            failureType + " can only occur on a CONDITIONAL step");
                }
                requireNoActionSummary(action, failureType);
            }
        }
    }

    private static void requireActionStepType(
            WorkflowStepType stepType, WorkflowFailureType failureType) {
        if (stepType != WorkflowStepType.ACTION) {
            throw new IllegalArgumentException(failureType + " can only occur on an ACTION step");
        }
    }

    private static void requireNoActionSummary(
            Optional<RecordedAction> action, WorkflowFailureType failureType) {
        if (action.isPresent()) {
            throw new IllegalArgumentException(failureType + " cannot carry an action summary");
        }
    }

    private static void requireActionSummaryWithSuccessStatus(
            Optional<RecordedAction> action, WorkflowFailureType failureType) {
        if (action.isEmpty()) {
            throw new IllegalArgumentException(failureType + " must carry an action summary");
        }
        if (action.get().status() != ActionStatus.SUCCESS) {
            throw new IllegalArgumentException(
                    failureType + "'s action summary must report ActionStatus.SUCCESS");
        }
    }

    private static RecordedAction requireActionSummaryWithNonSuccessStatus(
            Optional<RecordedAction> action, WorkflowFailureType failureType) {
        if (action.isEmpty()) {
            throw new IllegalArgumentException(failureType + " must carry an action summary");
        }
        if (action.get().status() == ActionStatus.SUCCESS) {
            throw new IllegalArgumentException(
                    failureType + "'s action summary must not report ActionStatus.SUCCESS");
        }
        return action.get();
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
                                                || failureType == ActionFailureType.BACKEND_FAILURE;
                                case REAL ->
                                        failureType == ActionFailureType.TARGET_NOT_INTERACTABLE
                                                || failureType
                                                        == ActionFailureType
                                                                .ACTION_NOT_SUPPORTED_BY_TARGET
                                                || failureType == ActionFailureType.BACKEND_FAILURE
                                                || failureType == ActionFailureType.UPLOAD_FAILURE
                                                || failureType
                                                        == ActionFailureType.DOWNLOAD_FAILURE;
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
}

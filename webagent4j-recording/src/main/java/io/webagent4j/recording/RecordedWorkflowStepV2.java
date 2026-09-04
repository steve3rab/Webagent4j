package io.webagent4j.recording;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowPlanOutput;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe, recorded outcome of one workflow step, as captured inside a {@link
 * RecordedExecutionNodeV2}.
 *
 * <p>Carries the same shape invariants as Recording V1's {@link RecordedWorkflowStep} - this type
 * is deliberately not built on top of it, so Recording V2 depends on no non-contractual internal
 * detail of V1 - with one structural difference: {@link #output()} is a typed {@link
 * WorkflowPlanOutput} (name, declared type, secret classification) rather than a bare variable
 * name, so a V2 recording states not just that a step published an output but what kind of value it
 * published - never the value itself, exactly as {@link WorkflowPlanOutput} already documents for
 * the deterministic execution plan it was designed for.
 *
 * @param stepId the step's identifier
 * @param stepType the step's broad category
 * @param status the step's terminal status
 * @param condition mirrors {@code WorkflowStepResult#condition}: a guard outcome for {@code
 *     ACTION}/{@code ASSIGN} ({@code false} always implies {@code SKIPPED}), or a {@code
 *     CONDITIONAL} step's mandatory branch decision ({@code false} never implies {@code SKIPPED})
 * @param output the typed variable this step published, if it produced one - metadata only, never a
 *     value
 * @param failure the recorded failure, if {@code status} is {@link WorkflowStepStatus#FAILED}
 * @param action a safe recorded projection of the underlying action result, for an {@code ACTION}
 *     step that reached execution
 */
public record RecordedWorkflowStepV2(
        WorkflowStepId stepId,
        WorkflowStepType stepType,
        WorkflowStepStatus status,
        Optional<RecordedCondition> condition,
        Optional<WorkflowPlanOutput> output,
        Optional<RecordedFailure> failure,
        Optional<RecordedAction> action) {

    /** Validates step data. */
    public RecordedWorkflowStepV2 {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(stepType, "stepType");
        Objects.requireNonNull(status, "status");
        condition = Objects.requireNonNull(condition, "condition");
        output = Objects.requireNonNull(output, "output");
        failure = Objects.requireNonNull(failure, "failure");
        action = Objects.requireNonNull(action, "action");
        if (status == WorkflowStepStatus.FAILED && failure.isEmpty()) {
            throw new IllegalArgumentException("a FAILED step must carry a failure");
        }
        if (status != WorkflowStepStatus.FAILED && failure.isPresent()) {
            throw new IllegalArgumentException("only a FAILED step may carry a failure");
        }
        if (status == WorkflowStepStatus.FAILED && output.isPresent()) {
            throw new IllegalArgumentException("a FAILED step cannot carry a published output");
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
                && output.isEmpty()) {
            throw new IllegalArgumentException(
                    "a SUCCEEDED ASSIGN step must carry a published output");
        }
        if (status == WorkflowStepStatus.SKIPPED) {
            if (condition.isEmpty()) {
                throw new IllegalArgumentException("a SKIPPED step must carry a condition outcome");
            }
            if (condition.get().outcome()) {
                throw new IllegalArgumentException(
                        "a SKIPPED step's condition outcome must be false");
            }
            if (output.isPresent()) {
                throw new IllegalArgumentException("a SKIPPED step cannot carry an output");
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
            if (output.isPresent()) {
                throw new IllegalArgumentException("a NOT_RUN step cannot carry an output");
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
            if (output.isPresent()) {
                throw new IllegalArgumentException(
                        "a CONDITIONAL step cannot carry a published output");
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
     * produce for a FAILED step - identical taxonomy to {@link RecordedWorkflowStep}'s own private
     * helper of the same purpose, kept as a separate copy rather than a shared call so this type
     * carries no compile-time dependency on Recording V1's internals.
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
}

package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowResultContractTest {

    private static final WorkflowStepId STEP_ONE = new WorkflowStepId("s1");
    private static final WorkflowStepId STEP_TWO = new WorkflowStepId("s2");

    @Test
    void failureTaxonomyMatchesTheEngineProjection() {
        assertThatThrownBy(
                        () ->
                                failure(
                                        WorkflowFailureType.ACTION_FAILED,
                                        STEP_ONE,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WorkflowFailure(
                                        WorkflowFailureType.STEP_EXCEPTION,
                                        "failed",
                                        Optional.of(STEP_ONE),
                                        Optional.empty(),
                                        Optional.of(ActionFailureType.BACKEND_FAILURE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WorkflowFailure(
                                        WorkflowFailureType.MISSING_REQUIRED_INPUT,
                                        "missing",
                                        Optional.of(STEP_ONE),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WorkflowFailure(
                                        WorkflowFailureType.MISSING_VARIABLE,
                                        "missing",
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actionSummaryRejectsImpossibleExecutionPairs() {
        assertThatThrownBy(
                        () ->
                                new WorkflowActionSummary(
                                        new ActionId("action"),
                                        ActionType.CLICK,
                                        ActionStatus.SUCCESS,
                                        ActionExecutionMode.NOT_EXECUTED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new WorkflowActionSummary(
                                        new ActionId("action"),
                                        ActionType.CLICK,
                                        ActionStatus.CANCELLED,
                                        ActionExecutionMode.DRY_RUN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepShapesMatchEngineReachableOutcomes() {
        WorkflowFailure actionFailure =
                failure(
                        WorkflowFailureType.ACTION_FAILED,
                        STEP_ONE,
                        Optional.of(ActionFailureType.BACKEND_FAILURE));

        assertThatThrownBy(
                        () ->
                                step(
                                        STEP_ONE,
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.SKIPPED,
                                        Optional.of(new WorkflowConditionResult(true, "enabled")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                step(
                                        STEP_ONE,
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.FAILED,
                                        Optional.empty(),
                                        Optional.of("output"),
                                        Optional.of(actionFailure),
                                        Optional.of(actionSummary(ActionStatus.EXECUTION_FAILED))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                step(
                                        STEP_ONE,
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                step(
                                        STEP_ONE,
                                        WorkflowStepType.ASSIGN,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completedResultRejectsImpossibleStepStatusesAndDuplicateIds() {
        WorkflowStepResult succeeded = succeededAssign(STEP_ONE);

        assertThatThrownBy(
                        () ->
                                result(
                                        WorkflowStatus.COMPLETED,
                                        List.of(notRun(STEP_ONE)),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                result(
                                        WorkflowStatus.COMPLETED,
                                        List.of(succeeded, succeeded),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroStepCompletedAndPreflightFailureResults() {
        assertThatThrownBy(() -> result(WorkflowStatus.COMPLETED, List.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        WorkflowFailure preflightFailure =
                new WorkflowFailure(
                        WorkflowFailureType.MISSING_REQUIRED_INPUT,
                        "missing",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
        assertThatThrownBy(
                        () ->
                                result(
                                        WorkflowStatus.FAILED,
                                        List.of(),
                                        Optional.of(preflightFailure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actionFailedProjectionEnforcesTheCompleteActionOutcomeMatrix() {
        for (ActionStatus status : ActionStatus.values()) {
            for (ActionExecutionMode executionMode : ActionExecutionMode.values()) {
                for (ActionFailureType failureType : ActionFailureType.values()) {
                    WorkflowFailure failure =
                            failure(
                                    WorkflowFailureType.ACTION_FAILED,
                                    STEP_ONE,
                                    Optional.of(failureType));
                    Runnable construction =
                            () ->
                                    step(
                                            STEP_ONE,
                                            WorkflowStepType.ACTION,
                                            WorkflowStepStatus.FAILED,
                                            Optional.empty(),
                                            Optional.empty(),
                                            Optional.of(failure),
                                            Optional.of(actionSummary(status, executionMode)));
                    String description = status + "/" + executionMode + "/" + failureType;
                    if (isValidActionFailureOutcome(status, executionMode, failureType)) {
                        assertThatCode(construction::run)
                                .as(description)
                                .doesNotThrowAnyException();
                    } else {
                        assertThatThrownBy(construction::run)
                                .as(description)
                                .isInstanceOf(IllegalArgumentException.class);
                    }
                }
            }
        }
    }

    @Test
    void preflightFailureRequiresAnAllNotRunTrace() {
        WorkflowFailure failure =
                new WorkflowFailure(
                        WorkflowFailureType.MISSING_REQUIRED_INPUT,
                        "missing",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());

        assertThatThrownBy(
                        () ->
                                result(
                                        WorkflowStatus.FAILED,
                                        List.of(succeededAssign(STEP_ONE)),
                                        Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runtimeFailureRequiresOneMatchingFailFastStep() {
        WorkflowFailure failure =
                failure(
                        WorkflowFailureType.ACTION_FAILED,
                        STEP_ONE,
                        Optional.of(ActionFailureType.BACKEND_FAILURE));
        WorkflowStepResult failed =
                step(
                        STEP_ONE,
                        WorkflowStepType.ACTION,
                        WorkflowStepStatus.FAILED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(failure),
                        Optional.of(actionSummary(ActionStatus.EXECUTION_FAILED)));

        assertThatThrownBy(
                        () ->
                                result(
                                        WorkflowStatus.FAILED,
                                        List.of(failed, succeededAssign(STEP_TWO)),
                                        Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                result(
                                        WorkflowStatus.FAILED,
                                        List.of(notRun(STEP_ONE)),
                                        Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkflowFailure failure(
            WorkflowFailureType type,
            WorkflowStepId stepId,
            Optional<ActionFailureType> actionFailureType) {
        return new WorkflowFailure(
                type, "failed", Optional.of(stepId), Optional.empty(), actionFailureType);
    }

    private static WorkflowActionSummary actionSummary(ActionStatus status) {
        return actionSummary(status, ActionExecutionMode.REAL);
    }

    private static WorkflowActionSummary actionSummary(
            ActionStatus status, ActionExecutionMode executionMode) {
        return new WorkflowActionSummary(
                new ActionId("action"), ActionType.CLICK, status, executionMode);
    }

    private static WorkflowStepResult succeededAssign(WorkflowStepId stepId) {
        return step(
                stepId,
                WorkflowStepType.ASSIGN,
                WorkflowStepStatus.SUCCEEDED,
                Optional.empty(),
                Optional.of("output"),
                Optional.empty(),
                Optional.empty());
    }

    private static WorkflowStepResult notRun(WorkflowStepId stepId) {
        return step(
                stepId,
                WorkflowStepType.ASSIGN,
                WorkflowStepStatus.NOT_RUN,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static WorkflowStepResult step(
            WorkflowStepId stepId,
            WorkflowStepType stepType,
            WorkflowStepStatus status,
            Optional<WorkflowConditionResult> condition,
            Optional<String> output,
            Optional<WorkflowFailure> failure,
            Optional<WorkflowActionSummary> actionSummary) {
        return new WorkflowStepResult(
                stepId, stepType, status, condition, output, failure, actionSummary);
    }

    private static WorkflowResult result(
            WorkflowStatus status,
            List<WorkflowStepResult> steps,
            Optional<WorkflowFailure> failure) {
        return new WorkflowResult(
                new WorkflowId("workflow"), status, steps, WorkflowOutputs.empty(), failure);
    }

    private static boolean isValidActionFailureOutcome(
            ActionStatus status, ActionExecutionMode executionMode, ActionFailureType failureType) {
        return switch (status) {
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
                                                == ActionFailureType.POLICY_EVALUATION_FAILED;
                        case REAL ->
                                failureType == ActionFailureType.TARGET_NOT_INTERACTABLE
                                        || failureType
                                                == ActionFailureType.ACTION_NOT_SUPPORTED_BY_TARGET
                                        || failureType == ActionFailureType.BACKEND_FAILURE
                                        || failureType == ActionFailureType.UPLOAD_FAILURE
                                        || failureType == ActionFailureType.DOWNLOAD_FAILURE
                                        || failureType == ActionFailureType.POLICY_VIOLATION
                                        || failureType == ActionFailureType.STABILIZATION_FAILED;
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
    }
}

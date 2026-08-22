package io.webagent4j.recording;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowId;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Test-only helpers for building minimal, valid {@link WorkflowRecording} fixtures directly.
 *
 * <p>Every failure builder below is named after, and shaped exactly like, one specific {@code
 * WorkflowFailureType} that {@code WorkflowEngine} can genuinely produce (see {@code
 * ActionWorkflowStep#run}, {@code WorkflowEngine.Session#executeStep}, and {@code
 * WorkflowEngine.Session#validateAndSeedInputs}) - there is deliberately no generic {@code
 * failure(type, stepId)} helper, since one previously existed and hardcoded an {@code
 * ActionFailureType} for every type regardless of whether that type could legitimately carry one,
 * silently fabricating impossible fixtures and masking missing validation.
 */
final class RecordingFixtures {

    private RecordingFixtures() {}

    static RecordedAction action(ActionType type, ActionStatus status, ActionExecutionMode mode) {
        return new RecordedAction(ActionId.create(), type, status, mode);
    }

    static RecordedWorkflowStep succeededActionStep(String stepId, String outputVariableName) {
        return new RecordedWorkflowStep(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                WorkflowStepStatus.SUCCEEDED,
                Optional.empty(),
                Optional.ofNullable(outputVariableName),
                Optional.empty(),
                Optional.of(
                        action(ActionType.CLICK, ActionStatus.SUCCESS, ActionExecutionMode.REAL)));
    }

    static RecordedWorkflowStep succeededAssignStep(String stepId, String outputVariableName) {
        return new RecordedWorkflowStep(
                new WorkflowStepId(stepId),
                WorkflowStepType.ASSIGN,
                WorkflowStepStatus.SUCCEEDED,
                Optional.empty(),
                Optional.of(outputVariableName),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStep skippedStep(String stepId, boolean outcome, String description) {
        return new RecordedWorkflowStep(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                WorkflowStepStatus.SKIPPED,
                Optional.of(new RecordedCondition(outcome, description)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStep skippedAssignStep(
            String stepId, boolean outcome, String description) {
        return new RecordedWorkflowStep(
                new WorkflowStepId(stepId),
                WorkflowStepType.ASSIGN,
                WorkflowStepStatus.SKIPPED,
                Optional.of(new RecordedCondition(outcome, description)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStep notRunStep(String stepId) {
        return new RecordedWorkflowStep(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                WorkflowStepStatus.NOT_RUN,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static RecordedWorkflowStep notRunAssignStep(String stepId) {
        return new RecordedWorkflowStep(
                new WorkflowStepId(stepId),
                WorkflowStepType.ASSIGN,
                WorkflowStepStatus.NOT_RUN,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    // ---- Failure builders - one per WorkflowFailureType, matching exactly the shape the current
    // WorkflowEngine produces for that type (see RecordedFailure/RecordingInvariants Javadoc). ----

    /**
     * A preflight failure ({@code MISSING_REQUIRED_INPUT}, {@code INPUT_TYPE_MISMATCH}, or {@code
     * UNDECLARED_INPUT}): raised by {@code validateAndSeedInputs} before step 0 ever runs, so it
     * never carries a stepId, underlying exception type, or ActionFailureType.
     */
    static RecordedFailure preflightFailure(WorkflowFailureType type) {
        return new RecordedFailure(
                type, "safe message", Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** {@code CONDITION_EVALUATION_FAILED}: carries the guarded step's own stepId. */
    static RecordedFailure conditionEvaluationFailedFailure(String stepId) {
        return new RecordedFailure(
                WorkflowFailureType.CONDITION_EVALUATION_FAILED,
                "safe message",
                Optional.of(new WorkflowStepId(stepId)),
                Optional.of("java.lang.RuntimeException"),
                Optional.empty());
    }

    /** {@code MISSING_VARIABLE}: ACTION-only ({@code IWorkflowActionFactory#prepare} threw it). */
    static RecordedFailure missingVariableFailure(String stepId) {
        return new RecordedFailure(
                WorkflowFailureType.MISSING_VARIABLE,
                "safe message",
                Optional.of(new WorkflowStepId(stepId)),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * {@code ACTION_FACTORY_FAILED}: ACTION-only ({@code IWorkflowActionFactory#prepare} threw).
     */
    static RecordedFailure actionFactoryFailedFailure(String stepId) {
        return new RecordedFailure(
                WorkflowFailureType.ACTION_FACTORY_FAILED,
                "safe message",
                Optional.of(new WorkflowStepId(stepId)),
                Optional.of("java.lang.RuntimeException"),
                Optional.empty());
    }

    /** {@code STEP_EXCEPTION}: ACTION-only ({@code IPreparedAction#execute} threw). */
    static RecordedFailure stepExceptionFailure(String stepId) {
        return new RecordedFailure(
                WorkflowFailureType.STEP_EXCEPTION,
                "safe message",
                Optional.of(new WorkflowStepId(stepId)),
                Optional.of("java.lang.RuntimeException"),
                Optional.empty());
    }

    /**
     * {@code ACTION_FAILED}: ACTION-only; always carries the projected {@link ActionFailureType}
     * ({@code ActionResult}'s own invariant guarantees one is present for a non-success result).
     */
    static RecordedFailure actionFailedFailure(String stepId, ActionFailureType actionFailureType) {
        return new RecordedFailure(
                WorkflowFailureType.ACTION_FAILED,
                "safe message",
                Optional.of(new WorkflowStepId(stepId)),
                Optional.empty(),
                Optional.of(actionFailureType));
    }

    /** {@code NULL_OUTPUT}: ACTION-only (the action itself already succeeded). */
    static RecordedFailure nullOutputFailure(String stepId) {
        return new RecordedFailure(
                WorkflowFailureType.NULL_OUTPUT,
                "safe message",
                Optional.of(new WorkflowStepId(stepId)),
                Optional.empty(),
                Optional.empty());
    }

    /** {@code OUTPUT_TYPE_MISMATCH}: ACTION-only (the action itself already succeeded). */
    static RecordedFailure outputTypeMismatchFailure(String stepId) {
        return new RecordedFailure(
                WorkflowFailureType.OUTPUT_TYPE_MISMATCH,
                "safe message",
                Optional.of(new WorkflowStepId(stepId)),
                Optional.empty(),
                Optional.empty());
    }

    // ---- Step shape builders - the three action-summary shapes a FAILED step can carry. ----

    /** A FAILED ACTION step carrying no action summary (factory-stage or condition failures). */
    static RecordedWorkflowStep actionStepFailedNoSummary(String stepId, RecordedFailure failure) {
        return new RecordedWorkflowStep(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                WorkflowStepStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(failure),
                Optional.empty());
    }

    /** A FAILED ASSIGN step - only {@code CONDITION_EVALUATION_FAILED} ever reaches this shape. */
    static RecordedWorkflowStep assignStepFailedNoSummary(String stepId, RecordedFailure failure) {
        return new RecordedWorkflowStep(
                new WorkflowStepId(stepId),
                WorkflowStepType.ASSIGN,
                WorkflowStepStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(failure),
                Optional.empty());
    }

    /**
     * A FAILED ACTION step carrying an action summary ({@code ACTION_FAILED} with a non-success
     * status, or {@code NULL_OUTPUT}/{@code OUTPUT_TYPE_MISMATCH} with a {@code SUCCESS} status).
     */
    static RecordedWorkflowStep actionStepFailedWithSummary(
            String stepId, RecordedFailure failure, ActionStatus actionStatus) {
        ActionExecutionMode executionMode =
                actionStatus == ActionStatus.PRECONDITION_FAILED
                                || (actionStatus == ActionStatus.EXECUTION_FAILED
                                        && (failure.actionFailureType().orElseThrow()
                                                        == ActionFailureType.TARGET_NOT_FOUND
                                                || failure.actionFailureType().orElseThrow()
                                                        == ActionFailureType.TARGET_AMBIGUOUS))
                        ? ActionExecutionMode.NOT_EXECUTED
                        : ActionExecutionMode.REAL;
        return actionStepFailedWithSummary(stepId, failure, actionStatus, executionMode);
    }

    static RecordedWorkflowStep actionStepFailedWithSummary(
            String stepId,
            RecordedFailure failure,
            ActionStatus actionStatus,
            ActionExecutionMode executionMode) {
        return new RecordedWorkflowStep(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                WorkflowStepStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(failure),
                Optional.of(action(ActionType.CLICK, actionStatus, executionMode)));
    }

    static WorkflowRecording minimalCompleted(String workflowId) {
        return minimalCompleted(workflowId, List.of(succeededAssignStep("step-1", "output")));
    }

    static WorkflowRecording minimalCompleted(String workflowId, List<RecordedWorkflowStep> steps) {
        return new WorkflowRecording(
                RecordingSchemaVersion.V1,
                new RecordingId("recording-1"),
                Instant.parse("2026-01-01T00:00:00Z"),
                new WorkflowId(workflowId),
                WorkflowStatus.COMPLETED,
                steps,
                Optional.empty());
    }

    static WorkflowRecording minimalFailed(
            String workflowId, List<RecordedWorkflowStep> steps, RecordedFailure failure) {
        return new WorkflowRecording(
                RecordingSchemaVersion.V1,
                new RecordingId("recording-1"),
                Instant.parse("2026-01-01T00:00:00Z"),
                new WorkflowId(workflowId),
                WorkflowStatus.FAILED,
                steps,
                Optional.of(failure));
    }

    /** A recording exercising every optional field, both present and absent, in one document. */
    static WorkflowRecording richRecording() {
        RecordedFailure failure = actionFailedFailure("s4", ActionFailureType.TARGET_NOT_FOUND);
        return minimalFailed(
                "wf",
                List.of(
                        succeededActionStep("s1", "out1"),
                        succeededAssignStep("s2", "out2"),
                        skippedStep("s3", false, "equals(flag, true)"),
                        actionStepFailedWithSummary("s4", failure, ActionStatus.EXECUTION_FAILED),
                        notRunStep("s5")),
                failure);
    }
}

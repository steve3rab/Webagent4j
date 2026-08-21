package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowActionSummary;
import io.webagent4j.workflow.WorkflowConditionResult;
import io.webagent4j.workflow.WorkflowFailure;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowStepResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Captures a {@code WorkflowResult} into an immutable, secret-safe {@link WorkflowRecording}.
 *
 * <p><b>Secret safety is structural, not a redaction pass:</b> this class only ever reads {@code
 * WorkflowResult.workflowId()}, {@code .status()}, {@code .steps()}, and {@code .failure()} - each
 * of which is already restricted to safe, categorical, or previously-redacted data - and the
 * equivalent safe accessors on {@code WorkflowStepResult}, {@code WorkflowConditionResult}, {@code
 * WorkflowActionSummary}, and {@code WorkflowFailure}. It never calls {@link
 * WorkflowResult#output(io.webagent4j.workflow.WorkflowVariable)}, never reads {@code
 * WorkflowResult.outputs()}, and never inspects a raw {@code ActionResult} value or {@code
 * Throwable}: a secret cannot appear in a recording because the code path that could observe one is
 * simply never exercised, not because a value is masked afterward.
 */
public final class WorkflowRecorder {

    /** Creates a recorder. Stateless: a single instance may record any number of results. */
    public WorkflowRecorder() {}

    /**
     * Records {@code result} as an immutable {@link WorkflowRecording}.
     *
     * @param recordingId the caller-supplied identifier for the new recording
     * @param capturedAt the caller-supplied capture time
     * @param result the workflow execution outcome to record
     */
    public WorkflowRecording record(
            RecordingId recordingId, Instant capturedAt, WorkflowResult result) {
        Objects.requireNonNull(recordingId, "recordingId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(result, "result");
        List<RecordedWorkflowStep> steps = new ArrayList<>(result.steps().size());
        for (WorkflowStepResult step : result.steps()) {
            steps.add(recordStep(step));
        }
        return new WorkflowRecording(
                RecordingSchemaVersion.V1,
                recordingId,
                capturedAt,
                result.workflowId(),
                result.status(),
                steps,
                result.failure().map(WorkflowRecorder::recordFailure));
    }

    private static RecordedWorkflowStep recordStep(WorkflowStepResult step) {
        return new RecordedWorkflowStep(
                step.stepId(),
                step.stepType(),
                step.status(),
                step.condition().map(WorkflowRecorder::recordCondition),
                step.outputVariableName(),
                step.failure().map(WorkflowRecorder::recordFailure),
                step.actionSummary().map(WorkflowRecorder::recordAction));
    }

    private static RecordedCondition recordCondition(WorkflowConditionResult condition) {
        return new RecordedCondition(condition.outcome(), condition.description());
    }

    private static RecordedAction recordAction(WorkflowActionSummary action) {
        return new RecordedAction(
                action.actionId(), action.actionType(), action.status(), action.executionMode());
    }

    private static RecordedFailure recordFailure(WorkflowFailure failure) {
        return new RecordedFailure(
                failure.type(),
                failure.safeMessage(),
                failure.stepId(),
                failure.underlyingTypeName(),
                failure.actionFailureType());
    }
}

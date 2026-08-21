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
 * Captures a {@code WorkflowResult} into an immutable {@link WorkflowRecording}.
 *
 * <p><b>Secret-safety boundary:</b> raw workflow value channels are excluded structurally. This
 * recorder never reads {@code WorkflowInputs}, raw {@code WorkflowOutputs}, {@link
 * WorkflowResult#output(io.webagent4j.workflow.WorkflowVariable)}, {@code ActionResult.value},
 * action observations or diagnostics, raw {@code Throwable} data, or the workflow secret registry.
 * Condition descriptions and failure messages copied from {@code WorkflowResult} have already been
 * redacted by {@code WorkflowEngine}.
 *
 * <p>Identifiers supplied by callers or action implementations, including {@link RecordingId} and
 * {@code ActionId}, are metadata and are persisted as supplied. This module performs no heuristic
 * classification or redaction of metadata, so callers and action implementations must keep those
 * identifiers non-sensitive.
 */
public final class WorkflowRecorder {

    /** Creates a recorder. Stateless: a single instance may record any number of results. */
    public WorkflowRecorder() {}

    /**
     * Records {@code result} as an immutable {@link WorkflowRecording}.
     *
     * @param recordingId the non-sensitive caller-supplied identifier, persisted verbatim
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

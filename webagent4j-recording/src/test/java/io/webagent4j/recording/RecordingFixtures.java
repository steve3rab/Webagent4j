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

/** Test-only helpers for building minimal, valid {@link WorkflowRecording} fixtures directly. */
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

    static RecordedWorkflowStep failedStep(String stepId, RecordedFailure failure) {
        return new RecordedWorkflowStep(
                new WorkflowStepId(stepId),
                WorkflowStepType.ACTION,
                WorkflowStepStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(failure),
                Optional.empty());
    }

    static RecordedFailure failure(WorkflowFailureType type, String stepId) {
        return new RecordedFailure(
                type,
                "safe message",
                Optional.ofNullable(stepId).map(WorkflowStepId::new),
                Optional.of("java.lang.RuntimeException"),
                Optional.of(ActionFailureType.TARGET_NOT_FOUND));
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
        return minimalFailed(
                "wf",
                List.of(
                        succeededActionStep("s1", "out1"),
                        succeededAssignStep("s2", "out2"),
                        skippedStep("s3", false, "equals(flag, true)"),
                        failedStep("s4", failure(WorkflowFailureType.ACTION_FAILED, "s4")),
                        notRunStep("s5")),
                failure(WorkflowFailureType.ACTION_FAILED, "s4"));
    }
}

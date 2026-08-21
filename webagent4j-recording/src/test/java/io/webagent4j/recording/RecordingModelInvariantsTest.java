package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowId;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecordingModelInvariantsTest {

    @Test
    void failedStepWithoutFailureIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.FAILED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonFailedStepWithFailureIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(
                                                RecordingFixtures.failure(
                                                        WorkflowFailureType.ACTION_FAILED, "s1")),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skippedStepWithoutConditionIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.SKIPPED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skippedStepWithOutputVariableIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.SKIPPED,
                                        Optional.of(new RecordedCondition(false, "d")),
                                        Optional.of("out"),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notRunStepWithConditionIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.NOT_RUN,
                                        Optional.of(new RecordedCondition(true, "d")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignStepWithActionIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ASSIGN,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.empty(),
                                        Optional.of("out"),
                                        Optional.empty(),
                                        Optional.of(
                                                RecordingFixtures.action(
                                                        io.webagent4j.action.ActionType.CLICK,
                                                        io.webagent4j.action.ActionStatus.SUCCESS,
                                                        io.webagent4j.action.ActionExecutionMode
                                                                .REAL))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void falseConditionOutcomeRequiresSkippedStatus() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.of(new RecordedCondition(false, "d")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedRecordingWithoutFailureIsRejected() {
        assertThatThrownBy(
                        () ->
                                new WorkflowRecording(
                                        RecordingSchemaVersion.V1,
                                        new RecordingId("r1"),
                                        Instant.EPOCH,
                                        new WorkflowId("wf"),
                                        WorkflowStatus.FAILED,
                                        List.of(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completedRecordingWithFailureIsRejected() {
        assertThatThrownBy(
                        () ->
                                new WorkflowRecording(
                                        RecordingSchemaVersion.V1,
                                        new RecordingId("r1"),
                                        Instant.EPOCH,
                                        new WorkflowId("wf"),
                                        WorkflowStatus.COMPLETED,
                                        List.of(),
                                        Optional.of(
                                                RecordingFixtures.failure(
                                                        WorkflowFailureType.ACTION_FAILED, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

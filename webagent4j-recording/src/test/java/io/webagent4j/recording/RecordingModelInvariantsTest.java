package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Test;

class RecordingModelInvariantsTest {

    @Test
    void recordedActionsRejectImpossibleExecutionPairs() {
        assertThatThrownBy(
                        () ->
                                new RecordedAction(
                                        ActionId.create(),
                                        ActionType.CLICK,
                                        ActionStatus.SUCCESS,
                                        ActionExecutionMode.NOT_EXECUTED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RecordedAction(
                                        ActionId.create(),
                                        ActionType.CLICK,
                                        ActionStatus.CANCELLED,
                                        ActionExecutionMode.DRY_RUN))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
                                                RecordingFixtures.actionFailedFailure(
                                                        "s1", ActionFailureType.BACKEND_FAILURE)),
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
                                                RecordingFixtures.preflightFailure(
                                                        WorkflowFailureType
                                                                .MISSING_REQUIRED_INPUT))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- INV-STEP ----

    /** INV-STEP-001: a SKIPPED step whose condition outcome is true is rejected. */
    @Test
    void invStep001SkippedWithTrueConditionIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.SKIPPED,
                                        Optional.of(new RecordedCondition(true, "d")),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-STEP-002: a SKIPPED step whose condition outcome is false is accepted. */
    @Test
    void invStep002SkippedWithFalseConditionIsAccepted() {
        RecordedWorkflowStep step =
                new RecordedWorkflowStep(
                        new WorkflowStepId("s1"),
                        WorkflowStepType.ACTION,
                        WorkflowStepStatus.SKIPPED,
                        Optional.of(new RecordedCondition(false, "d")),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
        assertThat(step.status()).isEqualTo(WorkflowStepStatus.SKIPPED);
    }

    /** INV-STEP-003: a FAILED step cannot carry a published output variable name. */
    @Test
    void invStep003FailedWithOutputVariableIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.FAILED,
                                        Optional.empty(),
                                        Optional.of("out"),
                                        Optional.of(
                                                RecordingFixtures.actionFailedFailure(
                                                        "s1", ActionFailureType.TARGET_NOT_FOUND)),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-STEP-004: a SUCCEEDED ACTION step without an action summary is rejected. */
    @Test
    void invStep004SucceededActionWithoutSummaryIsRejected() {
        assertThatThrownBy(
                        () ->
                                new RecordedWorkflowStep(
                                        new WorkflowStepId("s1"),
                                        WorkflowStepType.ACTION,
                                        WorkflowStepStatus.SUCCEEDED,
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-STEP-005: a FAILED ACTION step without an action summary remains allowed - for example
     * ACTION_FACTORY_FAILED, whose factory never reached the backend at all.
     */
    @Test
    void invStep005FailedActionWithoutSummaryIsAccepted() {
        RecordedWorkflowStep step =
                new RecordedWorkflowStep(
                        new WorkflowStepId("s1"),
                        WorkflowStepType.ACTION,
                        WorkflowStepStatus.FAILED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(RecordingFixtures.actionFactoryFailedFailure("s1")),
                        Optional.empty());
        assertThat(step.action()).isEmpty();
    }

    /**
     * INV-STEP-006: a FAILED ACTION step with an action summary remains allowed - for example
     * ACTION_FAILED, where the backend genuinely ran and reported a non-success status.
     */
    @Test
    void invStep006FailedActionWithSummaryIsAccepted() {
        RecordedWorkflowStep step =
                new RecordedWorkflowStep(
                        new WorkflowStepId("s1"),
                        WorkflowStepType.ACTION,
                        WorkflowStepStatus.FAILED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(
                                RecordingFixtures.actionFailedFailure(
                                        "s1", ActionFailureType.BACKEND_FAILURE)),
                        Optional.of(
                                RecordingFixtures.action(
                                        io.webagent4j.action.ActionType.CLICK,
                                        io.webagent4j.action.ActionStatus.EXECUTION_FAILED,
                                        io.webagent4j.action.ActionExecutionMode.REAL)));
        assertThat(step.action()).isPresent();
    }

    @Test
    void zeroStepCompletedAndPreflightFailureRecordingsAreRejected() {
        assertThatThrownBy(
                        () -> recordingWith(WorkflowStatus.COMPLETED, List.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        RecordedFailure preflightFailure =
                RecordingFixtures.preflightFailure(WorkflowFailureType.MISSING_REQUIRED_INPUT);
        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.FAILED,
                                        List.of(),
                                        Optional.of(preflightFailure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- INV-GLOBAL ----

    private static WorkflowRecording recordingWith(
            WorkflowStatus status,
            List<RecordedWorkflowStep> steps,
            Optional<RecordedFailure> failure) {
        return new WorkflowRecording(
                RecordingSchemaVersion.V1,
                new RecordingId("r1"),
                Instant.EPOCH,
                new WorkflowId("wf"),
                status,
                steps,
                failure);
    }

    /** INV-GLOBAL-001: a COMPLETED recording cannot contain a FAILED step. */
    @Test
    void invGlobal001CompletedWithFailedStepIsRejected() {
        RecordedWorkflowStep failed =
                RecordingFixtures.actionStepFailedWithSummary(
                        "s1",
                        RecordingFixtures.actionFailedFailure(
                                "s1", ActionFailureType.TARGET_NOT_FOUND),
                        io.webagent4j.action.ActionStatus.EXECUTION_FAILED);

        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.COMPLETED,
                                        List.of(failed),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-GLOBAL-002: a COMPLETED recording cannot contain a NOT_RUN step. */
    @Test
    void invGlobal002CompletedWithNotRunStepIsRejected() {
        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.COMPLETED,
                                        List.of(RecordingFixtures.notRunStep("s1")),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-GLOBAL-003: a step succeeding after the FAILED step is rejected. */
    @Test
    void invGlobal003SuccessAfterFailedStepIsRejected() {
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("s2", ActionFailureType.TARGET_NOT_FOUND);
        List<RecordedWorkflowStep> steps =
                List.of(
                        RecordingFixtures.succeededAssignStep("s1", "o1"),
                        RecordingFixtures.actionStepFailedWithSummary(
                                "s2", failure, io.webagent4j.action.ActionStatus.EXECUTION_FAILED),
                        RecordingFixtures.succeededAssignStep("s3", "o3"));

        assertThatThrownBy(() -> recordingWith(WorkflowStatus.FAILED, steps, Optional.of(failure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-GLOBAL-004: two FAILED steps in the same recording are rejected. */
    @Test
    void invGlobal004MultipleFailedStepsAreRejected() {
        RecordedFailure failure1 =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        RecordedFailure failure2 =
                RecordingFixtures.actionFailedFailure("s2", ActionFailureType.TARGET_NOT_FOUND);
        List<RecordedWorkflowStep> steps =
                List.of(
                        RecordingFixtures.actionStepFailedWithSummary(
                                "s1", failure1, io.webagent4j.action.ActionStatus.EXECUTION_FAILED),
                        RecordingFixtures.actionStepFailedWithSummary(
                                "s2",
                                failure2,
                                io.webagent4j.action.ActionStatus.EXECUTION_FAILED));

        assertThatThrownBy(() -> recordingWith(WorkflowStatus.FAILED, steps, Optional.of(failure1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** INV-GLOBAL-005: duplicate step IDs are rejected. */
    @Test
    void invGlobal005DuplicateStepIdsAreRejected() {
        List<RecordedWorkflowStep> steps =
                List.of(
                        RecordingFixtures.succeededAssignStep("s1", "o1"),
                        RecordingFixtures.succeededAssignStep("s1", "o2"));

        assertThatThrownBy(() -> recordingWith(WorkflowStatus.COMPLETED, steps, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-GLOBAL-006: a valid preflight failure (all steps NOT_RUN, no failure stepId) is accepted.
     */
    @Test
    void invGlobal006ValidPreflightFailureIsAccepted() {
        RecordedFailure failure =
                RecordingFixtures.preflightFailure(WorkflowFailureType.MISSING_REQUIRED_INPUT);
        List<RecordedWorkflowStep> steps =
                List.of(RecordingFixtures.notRunStep("s1"), RecordingFixtures.notRunStep("s2"));

        WorkflowRecording recording =
                recordingWith(WorkflowStatus.FAILED, steps, Optional.of(failure));

        assertThat(recording.steps()).hasSize(2);
    }

    /**
     * INV-GLOBAL-007: a valid runtime fail-fast sequence (SUCCEEDED, SKIPPED, FAILED, NOT_RUN) is
     * accepted.
     */
    @Test
    void invGlobal007ValidRuntimeFailFastSequenceIsAccepted() {
        RecordedFailure failure =
                RecordingFixtures.actionFailedFailure("s3", ActionFailureType.TARGET_NOT_FOUND);
        List<RecordedWorkflowStep> steps =
                List.of(
                        RecordingFixtures.succeededAssignStep("s1", "o1"),
                        RecordingFixtures.skippedStep("s2", false, "d"),
                        RecordingFixtures.actionStepFailedWithSummary(
                                "s3", failure, io.webagent4j.action.ActionStatus.EXECUTION_FAILED),
                        RecordingFixtures.notRunStep("s4"));

        WorkflowRecording recording =
                recordingWith(WorkflowStatus.FAILED, steps, Optional.of(failure));

        assertThat(recording.steps()).hasSize(4);
    }

    /**
     * INV-GLOBAL-008: an overall failure whose stepId does not match the FAILED step is rejected.
     */
    @Test
    void invGlobal008OverallFailureStepIdMismatchIsRejected() {
        RecordedFailure stepFailure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        RecordedFailure overallFailure =
                RecordingFixtures.actionFailedFailure(
                        "wrong-step", ActionFailureType.TARGET_NOT_FOUND);
        List<RecordedWorkflowStep> steps =
                List.of(
                        RecordingFixtures.actionStepFailedWithSummary(
                                "s1",
                                stepFailure,
                                io.webagent4j.action.ActionStatus.EXECUTION_FAILED));

        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.FAILED, steps, Optional.of(overallFailure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * INV-GLOBAL-009: an overall failure whose type disagrees with the FAILED step's own failure is
     * rejected.
     */
    @Test
    void invGlobal009OverallFailureTypeMismatchIsRejected() {
        RecordedFailure stepFailure =
                RecordingFixtures.actionFailedFailure("s1", ActionFailureType.TARGET_NOT_FOUND);
        RecordedFailure overallFailure = RecordingFixtures.stepExceptionFailure("s1");
        List<RecordedWorkflowStep> steps =
                List.of(
                        RecordingFixtures.actionStepFailedWithSummary(
                                "s1",
                                stepFailure,
                                io.webagent4j.action.ActionStatus.EXECUTION_FAILED));

        assertThatThrownBy(
                        () ->
                                recordingWith(
                                        WorkflowStatus.FAILED, steps, Optional.of(overallFailure)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

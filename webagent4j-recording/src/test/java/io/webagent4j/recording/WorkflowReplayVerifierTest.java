package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionType;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowReplayVerifierTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorder recorder = new WorkflowRecorder();
    private final WorkflowReplayVerifier verifier = new WorkflowReplayVerifier();

    private WorkflowResult successfulExecution() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        WorkflowVariable<String> out1 = WorkflowVariable.publicValue("out1", String.class);
        WorkflowVariable<String> out2 = WorkflowVariable.publicValue("out2", String.class);
        Workflow workflow =
                Workflow.builder("wf-replay")
                        .requiredInput(flag)
                        .step(
                                WorkflowSteps.action(
                                                "s1",
                                                vars ->
                                                        new FakePreparedAction<>(
                                                                ActionResults.success("v1")),
                                                out1)
                                        .when(WorkflowConditions.isTrue(flag)))
                        .step(WorkflowSteps.assign("s2", out2, "literal"))
                        .build();
        return engine.execute(workflow, WorkflowInputs.builder().put(flag, true).build());
    }

    private WorkflowResult failingExecution() {
        Workflow workflow =
                Workflow.builder("wf-replay-fail")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            throw new RuntimeException("boom");
                                        }))
                        .build();
        return engine.execute(workflow, WorkflowInputs.empty());
    }

    private static WorkflowRecording withStep(
            WorkflowRecording base, int index, RecordedWorkflowStep step) {
        List<RecordedWorkflowStep> steps = new ArrayList<>(base.steps());
        steps.set(index, step);
        return new WorkflowRecording(
                base.schemaVersion(),
                base.recordingId(),
                base.capturedAt(),
                base.workflowId(),
                base.status(),
                steps,
                base.failure());
    }

    private static WorkflowRecording withExtraStep(
            WorkflowRecording base, RecordedWorkflowStep extra) {
        List<RecordedWorkflowStep> steps = new ArrayList<>(base.steps());
        steps.add(extra);
        return new WorkflowRecording(
                base.schemaVersion(),
                base.recordingId(),
                base.capturedAt(),
                base.workflowId(),
                base.status(),
                steps,
                base.failure());
    }

    private static WorkflowRecording withFailure(WorkflowRecording base, RecordedFailure failure) {
        return new WorkflowRecording(
                base.schemaVersion(),
                base.recordingId(),
                base.capturedAt(),
                base.workflowId(),
                base.status(),
                base.steps(),
                Optional.of(failure));
    }

    /**
     * REPLAY-001: two independent executions of the same workflow match despite a fresh ActionId.
     */
    @Test
    void replay001ExactMatchDespiteDifferentActionId() {
        WorkflowResult run1 = successfulExecution();
        WorkflowResult run2 = successfulExecution();
        assertThat(run1.steps().get(0).actionSummary().orElseThrow().actionId())
                .isNotEqualTo(run2.steps().get(0).actionSummary().orElseThrow().actionId());
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), run1);

        WorkflowReplayResult replay = verifier.verify(recording, run2);

        assertThat(replay.matches()).isTrue();
        assertThat(replay.mismatches()).isEmpty();
    }

    /** REPLAY-002: recordings that differ only in capturedAt still match the same actual result. */
    @Test
    void replay002CapturedAtIgnored() {
        WorkflowResult run = successfulExecution();
        WorkflowRecording recordingA = recorder.record(new RecordingId("r"), Instant.EPOCH, run);
        WorkflowRecording recordingB = recorder.record(new RecordingId("r"), Instant.now(), run);

        assertThat(verifier.verify(recordingA, run).matches()).isTrue();
        assertThat(verifier.verify(recordingB, run).matches()).isTrue();
    }

    /** REPLAY-003: a changed step status is detected. */
    @Test
    void replay003StepStatusChangeDetected() {
        WorkflowResult run = successfulExecution();
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), run);
        RecordedWorkflowStep original = recording.steps().get(0);
        RecordedWorkflowStep mutated =
                new RecordedWorkflowStep(
                        original.stepId(),
                        original.stepType(),
                        WorkflowStepStatus.FAILED,
                        original.condition(),
                        original.outputVariableName(),
                        Optional.of(
                                RecordingFixtures.failure(WorkflowFailureType.ACTION_FAILED, "s1")),
                        original.action());
        WorkflowRecording mutatedRecording = withStep(recording, 0, mutated);

        WorkflowReplayResult replay = verifier.verify(mutatedRecording, run);

        assertThat(replay.matches()).isFalse();
        assertThat(replay.mismatches())
                .extracting(WorkflowReplayMismatch::type)
                .contains(WorkflowReplayMismatchType.STEP_STATUS_MISMATCH);
    }

    /** REPLAY-004: a changed action type is detected. */
    @Test
    void replay004ActionTypeChangeDetected() {
        WorkflowResult run = successfulExecution();
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), run);
        RecordedWorkflowStep original = recording.steps().get(0);
        RecordedAction originalAction = original.action().orElseThrow();
        RecordedAction mutatedAction =
                new RecordedAction(
                        originalAction.actionId(),
                        ActionType.TYPE,
                        originalAction.status(),
                        originalAction.executionMode());
        RecordedWorkflowStep mutatedStep =
                new RecordedWorkflowStep(
                        original.stepId(),
                        original.stepType(),
                        original.status(),
                        original.condition(),
                        original.outputVariableName(),
                        original.failure(),
                        Optional.of(mutatedAction));
        WorkflowRecording mutatedRecording = withStep(recording, 0, mutatedStep);

        WorkflowReplayResult replay = verifier.verify(mutatedRecording, run);

        assertThat(replay.mismatches())
                .extracting(WorkflowReplayMismatch::type)
                .containsExactly(WorkflowReplayMismatchType.ACTION_TYPE_MISMATCH);
    }

    /** REPLAY-005: a changed action execution mode is detected. */
    @Test
    void replay005ExecutionModeChangeDetected() {
        WorkflowResult run = successfulExecution();
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), run);
        RecordedWorkflowStep original = recording.steps().get(0);
        RecordedAction originalAction = original.action().orElseThrow();
        RecordedAction mutatedAction =
                new RecordedAction(
                        originalAction.actionId(),
                        originalAction.actionType(),
                        originalAction.status(),
                        ActionExecutionMode.DRY_RUN);
        RecordedWorkflowStep mutatedStep =
                new RecordedWorkflowStep(
                        original.stepId(),
                        original.stepType(),
                        original.status(),
                        original.condition(),
                        original.outputVariableName(),
                        original.failure(),
                        Optional.of(mutatedAction));
        WorkflowRecording mutatedRecording = withStep(recording, 0, mutatedStep);

        WorkflowReplayResult replay = verifier.verify(mutatedRecording, run);

        assertThat(replay.mismatches())
                .extracting(WorkflowReplayMismatch::type)
                .containsExactly(WorkflowReplayMismatchType.ACTION_EXECUTION_MODE_MISMATCH);
    }

    /** REPLAY-006: a changed condition description is never reported as a mismatch. */
    @Test
    void replay006ConditionDescriptionIgnored() {
        WorkflowResult run = successfulExecution();
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), run);
        RecordedWorkflowStep original = recording.steps().get(0);
        RecordedCondition mutatedCondition =
                new RecordedCondition(
                        original.condition().orElseThrow().outcome(), "totally different text");
        RecordedWorkflowStep mutatedStep =
                new RecordedWorkflowStep(
                        original.stepId(),
                        original.stepType(),
                        original.status(),
                        Optional.of(mutatedCondition),
                        original.outputVariableName(),
                        original.failure(),
                        original.action());
        WorkflowRecording mutatedRecording = withStep(recording, 0, mutatedStep);

        WorkflowReplayResult replay = verifier.verify(mutatedRecording, run);

        assertThat(replay.matches()).isTrue();
    }

    /** REPLAY-007: a changed condition outcome is detected. */
    @Test
    void replay007ConditionOutcomeCompared() {
        WorkflowResult run = successfulExecution();
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), run);
        RecordedWorkflowStep original = recording.steps().get(0);
        RecordedCondition mutatedCondition =
                new RecordedCondition(false, original.condition().orElseThrow().description());
        RecordedWorkflowStep mutatedStep =
                new RecordedWorkflowStep(
                        original.stepId(),
                        original.stepType(),
                        WorkflowStepStatus.SKIPPED,
                        Optional.of(mutatedCondition),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
        WorkflowRecording mutatedRecording = withStep(recording, 0, mutatedStep);

        WorkflowReplayResult replay = verifier.verify(mutatedRecording, run);

        assertThat(replay.mismatches())
                .extracting(WorkflowReplayMismatch::type)
                .contains(WorkflowReplayMismatchType.CONDITION_OUTCOME_MISMATCH);
    }

    /** REPLAY-008: a changed failure message is never reported as a mismatch. */
    @Test
    void replay008FailureMessageIgnored() {
        WorkflowResult failed = failingExecution();
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), failed);
        RecordedFailure original = recording.failure().orElseThrow();
        RecordedFailure mutated =
                new RecordedFailure(
                        original.type(),
                        "a completely different safe message",
                        original.stepId(),
                        original.underlyingTypeName(),
                        original.actionFailureType());
        WorkflowRecording mutatedRecording = withFailure(recording, mutated);

        WorkflowReplayResult replay = verifier.verify(mutatedRecording, failed);

        assertThat(replay.matches()).isTrue();
    }

    /** REPLAY-009: a changed failure type is detected. */
    @Test
    void replay009FailureTypeCompared() {
        WorkflowResult failed = failingExecution();
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), failed);
        RecordedFailure original = recording.failure().orElseThrow();
        WorkflowFailureType differentType =
                original.type() == WorkflowFailureType.ACTION_FACTORY_FAILED
                        ? WorkflowFailureType.STEP_EXCEPTION
                        : WorkflowFailureType.ACTION_FACTORY_FAILED;
        RecordedFailure mutated =
                new RecordedFailure(
                        differentType,
                        original.safeMessage(),
                        original.stepId(),
                        original.underlyingTypeName(),
                        original.actionFailureType());
        WorkflowRecording mutatedRecording = withFailure(recording, mutated);

        WorkflowReplayResult replay = verifier.verify(mutatedRecording, failed);

        assertThat(replay.mismatches())
                .extracting(WorkflowReplayMismatch::type)
                .contains(WorkflowReplayMismatchType.FAILURE_TYPE_MISMATCH);
    }

    /**
     * REPLAY-010: an extra recorded step is reported as both a count mismatch and a missing step.
     */
    @Test
    void replay010StepCountMismatchAndMissingStepReported() {
        WorkflowResult run = successfulExecution();
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), run);
        WorkflowRecording mutatedRecording =
                withExtraStep(recording, RecordingFixtures.notRunStep("extra"));

        WorkflowReplayResult replay = verifier.verify(mutatedRecording, run);

        assertThat(replay.mismatches())
                .extracting(WorkflowReplayMismatch::type)
                .contains(
                        WorkflowReplayMismatchType.STEP_COUNT_MISMATCH,
                        WorkflowReplayMismatchType.MISSING_STEP);
    }

    /**
     * REPLAY-011: repeated verification of the same inputs produces an identically ordered result.
     */
    @Test
    void replay011MismatchOrderIsDeterministic() {
        WorkflowResult run = successfulExecution();
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), run);
        RecordedWorkflowStep original = recording.steps().get(0);
        RecordedAction originalAction = original.action().orElseThrow();
        RecordedAction mutatedAction =
                new RecordedAction(
                        originalAction.actionId(),
                        ActionType.TYPE,
                        originalAction.status(),
                        originalAction.executionMode());
        RecordedWorkflowStep mutatedStep =
                new RecordedWorkflowStep(
                        original.stepId(),
                        original.stepType(),
                        original.status(),
                        original.condition(),
                        original.outputVariableName(),
                        original.failure(),
                        Optional.of(mutatedAction));
        WorkflowRecording mutatedRecording = withStep(recording, 0, mutatedStep);

        WorkflowReplayResult first = verifier.verify(mutatedRecording, run);
        WorkflowReplayResult second = verifier.verify(mutatedRecording, run);

        assertThat(first.mismatches()).isEqualTo(second.mismatches());
    }
}

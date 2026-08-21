package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionType;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
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
        return failingExecutionWithMessage("boom");
    }

    /** Same workflow shape as {@link #failingExecution()}, with a caller-chosen factory message. */
    private WorkflowResult failingExecutionWithMessage(String message) {
        Workflow workflow =
                Workflow.builder("wf-replay-fail")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            throw new RuntimeException(message);
                                        }))
                        .build();
        return engine.execute(workflow, WorkflowInputs.empty());
    }

    /** Same workflow shape/id as {@link #failingExecution()}, but fails with STEP_EXCEPTION. */
    private WorkflowResult differentFailureTypeExecution() {
        Workflow workflow =
                Workflow.builder("wf-replay-fail")
                        .step(WorkflowSteps.action("s1", vars -> new ThrowingPreparedAction<>()))
                        .build();
        return engine.execute(workflow, WorkflowInputs.empty());
    }

    /** Same shape as {@link #successfulExecution()}'s guarded step, with the flag toggled. */
    private WorkflowResult conditionalExecution(boolean flag) {
        WorkflowVariable<Boolean> flagVar = WorkflowVariable.publicValue("flag", Boolean.class);
        WorkflowVariable<String> out1 = WorkflowVariable.publicValue("out1", String.class);
        Workflow workflow =
                Workflow.builder("wf-replay-status")
                        .requiredInput(flagVar)
                        .step(
                                WorkflowSteps.action(
                                                "s1",
                                                vars ->
                                                        new FakePreparedAction<>(
                                                                ActionResults.success("v1")),
                                                out1)
                                        .when(WorkflowConditions.isTrue(flagVar)))
                        .build();
        return engine.execute(workflow, WorkflowInputs.builder().put(flagVar, flag).build());
    }

    private WorkflowResult threeStepExecution() {
        Workflow workflow =
                Workflow.builder("wf-replay-count")
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("o1", String.class),
                                        "a"))
                        .step(
                                WorkflowSteps.assign(
                                        "s2",
                                        WorkflowVariable.publicValue("o2", String.class),
                                        "b"))
                        .step(
                                WorkflowSteps.assign(
                                        "s3",
                                        WorkflowVariable.publicValue("o3", String.class),
                                        "c"))
                        .build();
        return engine.execute(workflow, WorkflowInputs.empty());
    }

    private WorkflowResult twoStepExecution() {
        Workflow workflow =
                Workflow.builder("wf-replay-count")
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("o1", String.class),
                                        "a"))
                        .step(
                                WorkflowSteps.assign(
                                        "s2",
                                        WorkflowVariable.publicValue("o2", String.class),
                                        "b"))
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

    /**
     * REPLAY-003: a changed step status is detected. Uses two independently valid executions of the
     * same workflow shape (flag false vs. true) rather than mutating a recording into a
     * structurally impossible trace (a COMPLETED recording can never contain a FAILED step - see
     * {@link RecordingInvariants}).
     */
    @Test
    void replay003StepStatusChangeDetected() {
        WorkflowResult skipped = conditionalExecution(false);
        WorkflowResult succeeded = conditionalExecution(true);
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), skipped);

        WorkflowReplayResult replay = verifier.verify(recording, succeeded);

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

    /**
     * REPLAY-008: a changed failure message is never reported as a mismatch. Uses two independently
     * valid executions of the same workflow shape and failure type (ACTION_FACTORY_FAILED at "s1")
     * that differ only in the factory exception's message, rather than mutating a single
     * recording's top-level failure - {@link RecordingInvariants} now requires the overall failure
     * to be fully identical to the FAILED step's own failure within one recording (see {@code
     * RecordingInvariants} Javadoc), so {@code safeMessage} can no longer be mutated in isolation.
     */
    @Test
    void replay008FailureMessageIgnored() {
        WorkflowResult recorded = failingExecutionWithMessage("boom");
        WorkflowResult actual = failingExecutionWithMessage("a completely different message");
        assertThat(recorded.failure().orElseThrow().safeMessage())
                .isNotEqualTo(actual.failure().orElseThrow().safeMessage());
        WorkflowRecording recording =
                recorder.record(new RecordingId("r"), Instant.now(), recorded);

        WorkflowReplayResult replay = verifier.verify(recording, actual);

        assertThat(replay.matches()).isTrue();
    }

    /**
     * REPLAY-009: a changed failure type is detected. Uses two independently valid executions of
     * the same workflow shape that fail for different reasons (ACTION_FACTORY_FAILED vs.
     * STEP_EXCEPTION at the same step ID) rather than mutating only the top-level failure's {@code
     * type} - {@link RecordingInvariants} requires the overall failure's {@code type} to agree with
     * the FAILED step's own failure {@code type}, so that field cannot be mutated in isolation.
     */
    @Test
    void replay009FailureTypeCompared() {
        WorkflowResult baseline = failingExecution();
        WorkflowResult actual = differentFailureTypeExecution();
        assertThat(baseline.failure().orElseThrow().type())
                .isNotEqualTo(actual.failure().orElseThrow().type());
        WorkflowRecording recording =
                recorder.record(new RecordingId("r"), Instant.now(), baseline);

        WorkflowReplayResult replay = verifier.verify(recording, actual);

        assertThat(replay.mismatches())
                .extracting(WorkflowReplayMismatch::type)
                .contains(WorkflowReplayMismatchType.FAILURE_TYPE_MISMATCH);
    }

    /**
     * REPLAY-010: an extra recorded step is reported as both a count mismatch and a missing step.
     * Uses two independently valid, differently-sized COMPLETED executions rather than appending a
     * NOT_RUN step to an otherwise-COMPLETED recording, which {@link RecordingInvariants} forbids.
     */
    @Test
    void replay010StepCountMismatchAndMissingStepReported() {
        WorkflowResult three = threeStepExecution();
        WorkflowResult two = twoStepExecution();
        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), three);

        WorkflowReplayResult replay = verifier.verify(recording, two);

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

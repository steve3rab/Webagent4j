package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepId;
import io.webagent4j.workflow.WorkflowStepStatus;
import io.webagent4j.workflow.WorkflowStepType;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkflowRecorderTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorder recorder = new WorkflowRecorder();

    /** REC-001: a successful execution's every safe field is faithfully recorded. */
    @Test
    void rec001SuccessfulRecordingCapturesEverySafeField() {
        WorkflowVariable<String> input = WorkflowVariable.publicValue("name", String.class);
        WorkflowVariable<String> actionOutput =
                WorkflowVariable.publicValue("actionOut", String.class);
        WorkflowVariable<String> assignOutput =
                WorkflowVariable.publicValue("assignOut", String.class);
        Workflow workflow =
                Workflow.builder("wf-success")
                        .requiredInput(input)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success("clicked")),
                                        actionOutput))
                        .step(WorkflowSteps.assign("s2", assignOutput, "literal"))
                        .build();
        WorkflowResult result =
                engine.execute(workflow, WorkflowInputs.builder().put(input, "value").build());
        assertThat(result.completed()).isTrue();

        Instant capturedAt = Instant.parse("2026-01-01T00:00:00Z");
        WorkflowRecording recording = recorder.record(new RecordingId("rec-1"), capturedAt, result);

        assertThat(recording.schemaVersion()).isEqualTo(RecordingSchemaVersion.V1);
        assertThat(recording.recordingId()).isEqualTo(new RecordingId("rec-1"));
        assertThat(recording.capturedAt()).isEqualTo(capturedAt);
        assertThat(recording.workflowId()).isEqualTo(result.workflowId());
        assertThat(recording.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(recording.failure()).isEmpty();
        assertThat(recording.steps()).hasSize(2);

        RecordedWorkflowStep step1 = recording.steps().get(0);
        assertThat(step1.stepId()).isEqualTo(new WorkflowStepId("s1"));
        assertThat(step1.stepType()).isEqualTo(WorkflowStepType.ACTION);
        assertThat(step1.status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(step1.outputVariableName()).contains("actionOut");
        assertThat(step1.action()).isPresent();
        assertThat(step1.action().orElseThrow().actionType()).isEqualTo(ActionType.CLICK);
        assertThat(step1.action().orElseThrow().status()).isEqualTo(ActionStatus.SUCCESS);
        assertThat(step1.action().orElseThrow().executionMode())
                .isEqualTo(ActionExecutionMode.REAL);

        RecordedWorkflowStep step2 = recording.steps().get(1);
        assertThat(step2.stepType()).isEqualTo(WorkflowStepType.ASSIGN);
        assertThat(step2.action()).isEmpty();
        assertThat(step2.outputVariableName()).contains("assignOut");
    }

    /** REC-002: a failed execution preserves SUCCEEDED, FAILED, and NOT_RUN step statuses. */
    @Test
    void rec002FailedRecordingPreservesAllStepStatuses() {
        Workflow workflow =
                Workflow.builder("wf-fail")
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("v1", String.class),
                                        "ok"))
                        .step(
                                WorkflowSteps.action(
                                        "s2",
                                        vars -> {
                                            throw new RuntimeException("boom");
                                        }))
                        .step(
                                WorkflowSteps.assign(
                                        "s3",
                                        WorkflowVariable.publicValue("v3", String.class),
                                        "never"))
                        .build();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.completed()).isFalse();

        WorkflowRecording recording =
                recorder.record(new RecordingId("rec-2"), Instant.now(), result);

        assertThat(recording.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(recording.failure()).isPresent();
        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.ACTION_FACTORY_FAILED);

        assertThat(recording.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(recording.steps().get(1).status()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(recording.steps().get(1).failure()).isPresent();
        RecordedWorkflowStep notRun = recording.steps().get(2);
        assertThat(notRun.status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        assertThat(notRun.condition()).isEmpty();
        assertThat(notRun.outputVariableName()).isEmpty();
        assertThat(notRun.action()).isEmpty();
        assertThat(notRun.failure()).isEmpty();
    }

    /** REC-003: a preflight (pre-step-0) failure is a valid recording with no FAILED step. */
    @Test
    void rec003PreflightFailureIsValidWithoutAnyFailedStep() {
        WorkflowVariable<String> required = WorkflowVariable.publicValue("required", String.class);
        Workflow workflow =
                Workflow.builder("wf-preflight")
                        .requiredInput(required)
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("v1", String.class),
                                        "x"))
                        .build();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.MISSING_REQUIRED_INPUT);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rec-3"), Instant.now(), result);

        assertThat(recording.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(recording.failure()).isPresent();
        assertThat(recording.steps()).hasSize(1);
        assertThat(recording.steps().get(0).status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        assertThat(recording.steps()).noneMatch(step -> step.status() == WorkflowStepStatus.FAILED);
    }

    /** REC-SAFE-001: the recorder never observes a raw output value, secret or otherwise. */
    @Test
    void recSafe001RecorderNeverTouchesRawOutputValue() {
        String sentinel = "WA4J_RECORDER_SAFE_SENTINEL_11939";
        WorkflowVariable<String> secretOut = WorkflowVariable.secret("secretOut");
        Workflow workflow =
                Workflow.builder("wf-safe")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(sentinel)),
                                        secretOut))
                        .build();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.completed()).isTrue();
        assertThat(result.output(secretOut)).contains(sentinel);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rec-safe"), Instant.now(), result);

        assertThat(recording.toString()).doesNotContain(sentinel);
        for (RecordedWorkflowStep step : recording.steps()) {
            assertThat(step.toString()).doesNotContain(sentinel);
        }
    }
}

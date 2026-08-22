package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
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
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowRecorderTest {

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorder recorder = new WorkflowRecorder();
    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();

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

    // ==================== REC-FAIL ====================
    // For every WorkflowFailureType, a genuine WorkflowEngine execution records successfully -
    // proving RecordingInvariants/RecordedFailure/RecordedWorkflowStep are not over-tight for any
    // state the current engine can actually produce.

    /** REC-FAIL-001: MISSING_REQUIRED_INPUT (preflight) records successfully. */
    @Test
    void recFail001MissingRequiredInputRecordsSuccessfully() {
        WorkflowVariable<String> required = WorkflowVariable.publicValue("required", String.class);
        Workflow workflow =
                Workflow.builder("wf-rf-001")
                        .requiredInput(required)
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("v1", String.class),
                                        "x"))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.MISSING_REQUIRED_INPUT);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rf-001"), Instant.now(), result);

        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.MISSING_REQUIRED_INPUT);
    }

    /** REC-FAIL-002: INPUT_TYPE_MISMATCH (preflight) records successfully. */
    @Test
    void recFail002InputTypeMismatchRecordsSuccessfully() {
        WorkflowVariable<String> secretInput = WorkflowVariable.secret("s");
        Workflow workflow =
                Workflow.builder("wf-rf-002")
                        .requiredInput(secretInput)
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("v1", String.class),
                                        "x"))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder()
                        .put(WorkflowVariable.publicValue("s", String.class), "not-secret-typed")
                        .build();

        WorkflowResult result = engine.execute(workflow, inputs);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.INPUT_TYPE_MISMATCH);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rf-002"), Instant.now(), result);

        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.INPUT_TYPE_MISMATCH);
    }

    /** REC-FAIL-003: UNDECLARED_INPUT (preflight) records successfully. */
    @Test
    void recFail003UndeclaredInputRecordsSuccessfully() {
        WorkflowVariable<String> undeclared =
                WorkflowVariable.publicValue("undeclared", String.class);
        Workflow workflow =
                Workflow.builder("wf-rf-003")
                        .step(
                                WorkflowSteps.assign(
                                        "s1",
                                        WorkflowVariable.publicValue("v1", String.class),
                                        "x"))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(undeclared, "value").build();

        WorkflowResult result = engine.execute(workflow, inputs);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.UNDECLARED_INPUT);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rf-003"), Instant.now(), result);

        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.UNDECLARED_INPUT);
    }

    /** REC-FAIL-004: MISSING_VARIABLE records successfully. */
    @Test
    void recFail004MissingVariableRecordsSuccessfully() {
        WorkflowVariable<Boolean> flag = WorkflowVariable.publicValue("flag", Boolean.class);
        WorkflowVariable<String> produced = WorkflowVariable.publicValue("produced", String.class);
        Workflow workflow =
                Workflow.builder("wf-rf-004")
                        .requiredInput(flag)
                        .step(
                                WorkflowSteps.assign("producer", produced, "value")
                                        .when(WorkflowConditions.isTrue(flag)))
                        .step(
                                WorkflowSteps.action(
                                        "consumer",
                                        vars -> {
                                            vars.require(produced);
                                            return new FakePreparedAction<>(
                                                    ActionResults.success("ignored"));
                                        }))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(flag, false).build();

        WorkflowResult result = engine.execute(workflow, inputs);
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.MISSING_VARIABLE);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rf-004"), Instant.now(), result);

        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.MISSING_VARIABLE);
    }

    /** REC-FAIL-005: ACTION_FACTORY_FAILED records successfully. */
    @Test
    void recFail005ActionFactoryFailedRecordsSuccessfully() {
        Workflow workflow =
                Workflow.builder("wf-rf-005")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            throw new RuntimeException("boom");
                                        }))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.ACTION_FACTORY_FAILED);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rf-005"), Instant.now(), result);

        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.ACTION_FACTORY_FAILED);
    }

    /** REC-FAIL-006: STEP_EXCEPTION records successfully. */
    @Test
    void recFail006StepExceptionRecordsSuccessfully() {
        Workflow workflow =
                Workflow.builder("wf-rf-006")
                        .step(WorkflowSteps.action("s1", vars -> new ThrowingPreparedAction<>()))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.STEP_EXCEPTION);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rf-006"), Instant.now(), result);

        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.STEP_EXCEPTION);
    }

    /**
     * REC-FAIL-007: ACTION_FAILED records successfully, with a present ActionFailureType and a
     * non-success action summary.
     */
    @Test
    void recFail007ActionFailedRecordsSuccessfully() {
        Workflow workflow =
                Workflow.builder("wf-rf-007")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.failure(
                                                                ActionFailureType.TARGET_NOT_FOUND,
                                                                "not found"))))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.ACTION_FAILED);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rf-007"), Instant.now(), result);

        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.ACTION_FAILED);
        assertThat(recording.failure().orElseThrow().actionFailureType())
                .contains(ActionFailureType.TARGET_NOT_FOUND);
        RecordedWorkflowStep step = recording.steps().get(0);
        assertThat(step.action()).isPresent();
        assertThat(step.action().orElseThrow().status()).isNotEqualTo(ActionStatus.SUCCESS);
    }

    @Test
    void interruptedActionRoundTripsAcrossWorkflowRecordingAndJson() {
        for (ActionExecutionMode executionMode :
                Set.of(ActionExecutionMode.NOT_EXECUTED, ActionExecutionMode.REAL)) {
            Workflow workflow =
                    Workflow.builder("wf-interrupted-" + executionMode.name())
                            .step(
                                    WorkflowSteps.action(
                                            "s1",
                                            variables ->
                                                    new FakePreparedAction<>(
                                                            ActionResults.interrupted(
                                                                    executionMode))))
                            .build();

            WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
            WorkflowRecording recording =
                    recorder.record(
                            new RecordingId("rec-interrupted-" + executionMode.name()),
                            Instant.parse("2026-01-01T00:00:00Z"),
                            result);
            WorkflowRecording decoded = codec.decode(codec.encode(recording));

            assertThat(result.status()).isEqualTo(WorkflowStatus.FAILED);
            assertThat(result.failure().orElseThrow().actionFailureType())
                    .contains(ActionFailureType.INTERRUPTED);
            assertThat(decoded).isEqualTo(recording);
            assertThat(decoded.schemaVersion()).isEqualTo(RecordingSchemaVersion.V1);
            assertThat(decoded.steps().get(0).action().orElseThrow().status())
                    .isEqualTo(ActionStatus.CANCELLED);
            assertThat(decoded.steps().get(0).action().orElseThrow().executionMode())
                    .isEqualTo(executionMode);
        }
    }

    /** REC-FAIL-008: NULL_OUTPUT records successfully, with a SUCCESS-status action summary. */
    @Test
    void recFail008NullOutputRecordsSuccessfully() {
        WorkflowVariable<String> output = WorkflowVariable.publicValue("out", String.class);
        Workflow workflow =
                Workflow.builder("wf-rf-008")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(null)),
                                        output))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.NULL_OUTPUT);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rf-008"), Instant.now(), result);

        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.NULL_OUTPUT);
        RecordedWorkflowStep step = recording.steps().get(0);
        assertThat(step.action().orElseThrow().status()).isEqualTo(ActionStatus.SUCCESS);
    }

    /**
     * REC-FAIL-009: OUTPUT_TYPE_MISMATCH records successfully, with a SUCCESS-status action
     * summary.
     */
    @Test
    @SuppressWarnings("unchecked")
    void recFail009OutputTypeMismatchRecordsSuccessfully() {
        WorkflowVariable<Integer> intOutput = WorkflowVariable.publicValue("intOut", Integer.class);
        ActionResult<Integer> mismatched =
                (ActionResult<Integer>) (ActionResult<?>) ActionResults.success("not-an-int");
        Workflow workflow =
                Workflow.builder("wf-rf-009")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> new FakePreparedAction<>(mismatched),
                                        intOutput))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.OUTPUT_TYPE_MISMATCH);

        WorkflowRecording recording =
                recorder.record(new RecordingId("rf-009"), Instant.now(), result);

        assertThat(recording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.OUTPUT_TYPE_MISMATCH);
        RecordedWorkflowStep step = recording.steps().get(0);
        assertThat(step.action().orElseThrow().status()).isEqualTo(ActionStatus.SUCCESS);
    }

    /**
     * REC-FAIL-010: CONDITION_EVALUATION_FAILED records successfully, on both an ACTION and an
     * ASSIGN step - the one runtime failure type an ASSIGN step can carry.
     */
    @Test
    void recFail010ConditionEvaluationFailedRecordsSuccessfullyOnBothStepTypes() {
        IWorkflowCondition throwing =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        throw new RuntimeException("condition boom");
                    }

                    @Override
                    public String describe() {
                        return "throwing condition";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };

        Workflow actionWorkflow =
                Workflow.builder("wf-rf-010-action")
                        .step(
                                WorkflowSteps.action(
                                                "s1",
                                                vars ->
                                                        new FakePreparedAction<>(
                                                                ActionResults.success("ignored")))
                                        .when(throwing))
                        .build();
        WorkflowResult actionResult = engine.execute(actionWorkflow, WorkflowInputs.empty());
        assertThat(actionResult.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
        WorkflowRecording actionRecording =
                recorder.record(new RecordingId("rf-010-action"), Instant.now(), actionResult);
        assertThat(actionRecording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);

        Workflow assignWorkflow =
                Workflow.builder("wf-rf-010-assign")
                        .step(
                                WorkflowSteps.assign(
                                                "s1",
                                                WorkflowVariable.publicValue("v1", String.class),
                                                "x")
                                        .when(throwing))
                        .build();
        WorkflowResult assignResult = engine.execute(assignWorkflow, WorkflowInputs.empty());
        assertThat(assignResult.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
        WorkflowRecording assignRecording =
                recorder.record(new RecordingId("rf-010-assign"), Instant.now(), assignResult);
        assertThat(assignRecording.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
    }
}

package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowConditions;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecordingSecretSafetyTest {

    private static final String SENTINEL = "WA4J_RECORDING_SECRET_SENTINEL_55217";

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorder recorder = new WorkflowRecorder();
    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();

    /** SEC-REC-001: a secret input, used only inside an action factory closure, never appears. */
    @Test
    void secRec001SecretInputNeverSerialized() {
        WorkflowVariable<String> password = WorkflowVariable.secret("password");
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(password)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(
                                                                vars.require(password)))))
                        .build();
        WorkflowResult result =
                engine.execute(workflow, WorkflowInputs.builder().put(password, SENTINEL).build());
        assertThat(result.completed()).isTrue();

        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), result);
        String encoded = codec.encode(recording);

        assertThat(encoded).doesNotContain(SENTINEL);
    }

    /** SEC-REC-002: a secret output's real value never appears, though it is genuinely produced. */
    @Test
    void secRec002SecretOutputNeverSerialized() {
        WorkflowVariable<String> secretOutput = WorkflowVariable.secret("secretOutput");
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(SENTINEL)),
                                        secretOutput))
                        .build();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.completed()).isTrue();
        assertThat(result.output(secretOutput)).contains(SENTINEL);

        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), result);
        String encoded = codec.encode(recording);

        assertThat(encoded).doesNotContain(SENTINEL);
    }

    /** SEC-REC-003: a secret embedded in a thrown failure message stays redacted end to end. */
    @Test
    void secRec003SecretInFailureMessageStaysRedacted() {
        WorkflowVariable<String> password = WorkflowVariable.secret("password");
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(password)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            throw new RuntimeException(
                                                    "bad credential " + vars.require(password));
                                        }))
                        .build();
        WorkflowResult result =
                engine.execute(workflow, WorkflowInputs.builder().put(password, SENTINEL).build());
        assertThat(result.completed()).isFalse();

        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), result);
        String encoded = codec.encode(recording);

        assertThat(recording.failure().orElseThrow().safeMessage()).doesNotContain(SENTINEL);
        assertThat(encoded).doesNotContain(SENTINEL);
    }

    /** SEC-REC-004: a condition on a secret variable is redacted, and that redaction persists. */
    @Test
    void secRec004LateSecretConditionRedactionPersistsIntoRecording() {
        WorkflowVariable<String> password = WorkflowVariable.secret("password");
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(password)
                        .step(
                                WorkflowSteps.assign(
                                                "s1",
                                                WorkflowVariable.publicValue(
                                                        "marker", String.class),
                                                "reached")
                                        .when(WorkflowConditions.equals(password, SENTINEL)))
                        .build();
        WorkflowResult result =
                engine.execute(workflow, WorkflowInputs.builder().put(password, SENTINEL).build());
        assertThat(result.completed()).isTrue();

        WorkflowRecording recording = recorder.record(new RecordingId("r"), Instant.now(), result);
        String description = recording.steps().get(0).condition().orElseThrow().description();
        assertThat(description).doesNotContain(SENTINEL);

        String encoded = codec.encode(recording);
        assertThat(encoded).doesNotContain(SENTINEL);
    }
}

package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionId;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowResult;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecordingMetadataTrustBoundaryTest {

    private static final String METADATA_SENTINEL = "WA4J_METADATA_SENTINEL_918273";

    private final WorkflowEngine engine = new WorkflowEngine();
    private final WorkflowRecorder recorder = new WorkflowRecorder();
    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();

    /** META-TRUST-001: custom non-sensitive ActionId metadata is preserved verbatim. */
    @Test
    void metaTrust001CustomActionIdIsPreservedVerbatim() {
        WorkflowResult result = executeWithActionId("custom-correlation-id");

        WorkflowRecording recording = record(new RecordingId("recording-1"), result);
        String encoded = codec.encode(recording);

        assertThat(recording.steps().getFirst().action().orElseThrow().actionId())
                .isEqualTo(new ActionId("custom-correlation-id"));
        assertThat(encoded).contains("\"actionId\":\"custom-correlation-id\"");
    }

    /** META-TRUST-002: caller/action-supplied ActionId metadata is not redacted. */
    @Test
    void metaTrust002SensitiveLookingActionIdIsPersistedVerbatim() {
        WorkflowResult result = executeWithActionId(METADATA_SENTINEL);

        String encoded = codec.encode(record(new RecordingId("recording-2"), result));

        assertThat(encoded).contains("\"actionId\":\"" + METADATA_SENTINEL + "\"");
    }

    /** META-TRUST-003: a custom action can copy a workflow secret into unsanitized metadata. */
    @Test
    void metaTrust003WorkflowSecretCopiedIntoActionIdDemonstratesMetadataBoundary() {
        WorkflowVariable<String> secret = WorkflowVariable.secret("secret");
        Workflow workflow =
                Workflow.builder("metadata-secret-boundary")
                        .requiredInput(secret)
                        .step(
                                WorkflowSteps.action(
                                        "custom-action",
                                        variables ->
                                                new FakePreparedAction<>(
                                                        ActionResults.successWithActionId(
                                                                new ActionId(
                                                                        variables.require(secret)),
                                                                "done"))))
                        .build();
        WorkflowResult result =
                engine.execute(
                        workflow, WorkflowInputs.builder().put(secret, METADATA_SENTINEL).build());
        assertThat(result.completed()).isTrue();

        String encoded = codec.encode(record(new RecordingId("recording-3"), result));

        assertThat(encoded).contains("\"actionId\":\"" + METADATA_SENTINEL + "\"");
    }

    /** META-TRUST-004: caller-supplied RecordingId metadata is persisted verbatim. */
    @Test
    void metaTrust004RecordingIdIsPersistedVerbatim() {
        WorkflowResult result = executeWithActionId("safe-action-id");

        String encoded = codec.encode(record(new RecordingId("caller-metadata-123"), result));

        assertThat(encoded).contains("\"recordingId\":\"caller-metadata-123\"");
    }

    private WorkflowResult executeWithActionId(String actionId) {
        Workflow workflow =
                Workflow.builder("metadata-boundary")
                        .step(
                                WorkflowSteps.action(
                                        "custom-action",
                                        variables ->
                                                new FakePreparedAction<>(
                                                        ActionResults.successWithActionId(
                                                                new ActionId(actionId), "done"))))
                        .build();
        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());
        assertThat(result.completed()).isTrue();
        return result;
    }

    private WorkflowRecording record(RecordingId recordingId, WorkflowResult result) {
        return recorder.record(recordingId, Instant.parse("2026-01-01T00:00:00Z"), result);
    }
}

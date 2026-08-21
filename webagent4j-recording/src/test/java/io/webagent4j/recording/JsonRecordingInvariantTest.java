package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.workflow.WorkflowStatus;
import io.webagent4j.workflow.WorkflowStepStatus;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link JsonWorkflowRecordingCodec#decode} rejects, as external JSON, exactly the same
 * structurally impossible executions that {@link RecordingInvariants} rejects for direct
 * construction (see {@link RecordingModelInvariantsTest}'s {@code INV-GLOBAL}/{@code INV-STEP}
 * cases) - there is no decode-only construction path that could bypass those checks.
 */
class JsonRecordingInvariantTest {

    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();

    /** JSON-INV-001: a COMPLETED document containing a FAILED step is rejected. */
    @Test
    void jsonInv001CompletedWithFailedStepIsRejected() {
        String json =
                "{\"schemaVersion\":1,\"recordingId\":\"r1\",\"capturedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"workflow\":{\"workflowId\":\"wf\",\"status\":\"COMPLETED\",\"steps\":["
                        + "{\"stepId\":\"s1\",\"stepType\":\"ASSIGN\",\"status\":\"SUCCEEDED\","
                        + "\"condition\":null,\"outputVariableName\":\"o1\",\"failure\":null,\"action\":null},"
                        + "{\"stepId\":\"s2\",\"stepType\":\"ACTION\",\"status\":\"FAILED\","
                        + "\"condition\":null,\"outputVariableName\":null,\"failure\":{\"type\":\"ACTION_FAILED\","
                        + "\"safeMessage\":\"x\",\"stepId\":\"s2\",\"underlyingTypeName\":null,"
                        + "\"actionFailureType\":null},\"action\":null}]},\"failure\":null}";

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(RecordingFormatException.class);
    }

    /** JSON-INV-002: a COMPLETED document containing a NOT_RUN step is rejected. */
    @Test
    void jsonInv002CompletedWithNotRunStepIsRejected() {
        String json =
                "{\"schemaVersion\":1,\"recordingId\":\"r1\",\"capturedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"workflow\":{\"workflowId\":\"wf\",\"status\":\"COMPLETED\",\"steps\":["
                        + "{\"stepId\":\"s1\",\"stepType\":\"ASSIGN\",\"status\":\"SUCCEEDED\","
                        + "\"condition\":null,\"outputVariableName\":\"o1\",\"failure\":null,\"action\":null},"
                        + "{\"stepId\":\"s2\",\"stepType\":\"ACTION\",\"status\":\"NOT_RUN\","
                        + "\"condition\":null,\"outputVariableName\":null,\"failure\":null,\"action\":null}]},"
                        + "\"failure\":null}";

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(RecordingFormatException.class);
    }

    /** JSON-INV-003: a FAILED document with a step succeeding after the FAILED step is rejected. */
    @Test
    void jsonInv003SuccessAfterFailedStepIsRejected() {
        String json =
                "{\"schemaVersion\":1,\"recordingId\":\"r1\",\"capturedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"workflow\":{\"workflowId\":\"wf\",\"status\":\"FAILED\",\"steps\":["
                        + "{\"stepId\":\"s1\",\"stepType\":\"ASSIGN\",\"status\":\"SUCCEEDED\","
                        + "\"condition\":null,\"outputVariableName\":\"o1\",\"failure\":null,\"action\":null},"
                        + "{\"stepId\":\"s2\",\"stepType\":\"ACTION\",\"status\":\"FAILED\","
                        + "\"condition\":null,\"outputVariableName\":null,\"failure\":{\"type\":\"ACTION_FAILED\","
                        + "\"safeMessage\":\"x\",\"stepId\":\"s2\",\"underlyingTypeName\":null,"
                        + "\"actionFailureType\":null},\"action\":null},"
                        + "{\"stepId\":\"s3\",\"stepType\":\"ASSIGN\",\"status\":\"SUCCEEDED\","
                        + "\"condition\":null,\"outputVariableName\":\"o3\",\"failure\":null,\"action\":null}]},"
                        + "\"failure\":{\"type\":\"ACTION_FAILED\",\"safeMessage\":\"x\",\"stepId\":\"s2\","
                        + "\"underlyingTypeName\":null,\"actionFailureType\":null}}";

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(RecordingFormatException.class);
    }

    /** JSON-INV-004: duplicate step IDs are rejected. */
    @Test
    void jsonInv004DuplicateStepIdsAreRejected() {
        String json =
                "{\"schemaVersion\":1,\"recordingId\":\"r1\",\"capturedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"workflow\":{\"workflowId\":\"wf\",\"status\":\"COMPLETED\",\"steps\":["
                        + "{\"stepId\":\"s1\",\"stepType\":\"ASSIGN\",\"status\":\"SUCCEEDED\","
                        + "\"condition\":null,\"outputVariableName\":\"o1\",\"failure\":null,\"action\":null},"
                        + "{\"stepId\":\"s1\",\"stepType\":\"ASSIGN\",\"status\":\"SUCCEEDED\","
                        + "\"condition\":null,\"outputVariableName\":\"o2\",\"failure\":null,\"action\":null}]},"
                        + "\"failure\":null}";

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(RecordingFormatException.class);
    }

    /** JSON-INV-005: a SKIPPED step whose condition outcome is true is rejected. */
    @Test
    void jsonInv005SkippedWithTrueConditionOutcomeIsRejected() {
        String json =
                "{\"schemaVersion\":1,\"recordingId\":\"r1\",\"capturedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"workflow\":{\"workflowId\":\"wf\",\"status\":\"COMPLETED\",\"steps\":["
                        + "{\"stepId\":\"s1\",\"stepType\":\"ACTION\",\"status\":\"SKIPPED\","
                        + "\"condition\":{\"outcome\":true,\"description\":\"d\"},\"outputVariableName\":null,"
                        + "\"failure\":null,\"action\":null}]},\"failure\":null}";

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(RecordingFormatException.class);
    }

    /** JSON-INV-006: a FAILED step with an output variable name is rejected. */
    @Test
    void jsonInv006FailedStepWithOutputVariableIsRejected() {
        String json =
                "{\"schemaVersion\":1,\"recordingId\":\"r1\",\"capturedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"workflow\":{\"workflowId\":\"wf\",\"status\":\"FAILED\",\"steps\":["
                        + "{\"stepId\":\"s1\",\"stepType\":\"ACTION\",\"status\":\"FAILED\","
                        + "\"condition\":null,\"outputVariableName\":\"o1\",\"failure\":{\"type\":\"ACTION_FAILED\","
                        + "\"safeMessage\":\"x\",\"stepId\":\"s1\",\"underlyingTypeName\":null,"
                        + "\"actionFailureType\":null},\"action\":null}]},"
                        + "\"failure\":{\"type\":\"ACTION_FAILED\",\"safeMessage\":\"x\",\"stepId\":\"s1\","
                        + "\"underlyingTypeName\":null,\"actionFailureType\":null}}";

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(RecordingFormatException.class);
    }

    /** JSON-INV-007: an overall failure stepId that does not match the FAILED step is rejected. */
    @Test
    void jsonInv007OverallFailureStepIdMismatchIsRejected() {
        String json =
                "{\"schemaVersion\":1,\"recordingId\":\"r1\",\"capturedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"workflow\":{\"workflowId\":\"wf\",\"status\":\"FAILED\",\"steps\":["
                        + "{\"stepId\":\"s1\",\"stepType\":\"ACTION\",\"status\":\"FAILED\","
                        + "\"condition\":null,\"outputVariableName\":null,\"failure\":{\"type\":\"ACTION_FAILED\","
                        + "\"safeMessage\":\"x\",\"stepId\":\"s1\",\"underlyingTypeName\":null,"
                        + "\"actionFailureType\":null},\"action\":null}]},"
                        + "\"failure\":{\"type\":\"ACTION_FAILED\",\"safeMessage\":\"x\",\"stepId\":\"wrong\","
                        + "\"underlyingTypeName\":null,\"actionFailureType\":null}}";

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(RecordingFormatException.class);
    }

    /** JSON-INV-008: a valid preflight FAILED/all-NOT_RUN document decodes successfully. */
    @Test
    void jsonInv008ValidPreflightFailureDocumentDecodes() {
        String json =
                "{\"schemaVersion\":1,\"recordingId\":\"r1\",\"capturedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"workflow\":{\"workflowId\":\"wf\",\"status\":\"FAILED\",\"steps\":["
                        + "{\"stepId\":\"s1\",\"stepType\":\"ACTION\",\"status\":\"NOT_RUN\","
                        + "\"condition\":null,\"outputVariableName\":null,\"failure\":null,\"action\":null}]},"
                        + "\"failure\":{\"type\":\"MISSING_REQUIRED_INPUT\",\"safeMessage\":\"x\",\"stepId\":null,"
                        + "\"underlyingTypeName\":null,\"actionFailureType\":null}}";

        WorkflowRecording decoded = codec.decode(json);

        assertThat(decoded.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(decoded.steps()).hasSize(1);
        assertThat(decoded.steps().get(0).status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
    }

    /** JSON-INV-009: a valid runtime fail-fast document decodes successfully. */
    @Test
    void jsonInv009ValidRuntimeFailFastDocumentDecodes() {
        String json =
                "{\"schemaVersion\":1,\"recordingId\":\"r1\",\"capturedAt\":\"2026-01-01T00:00:00Z\","
                        + "\"workflow\":{\"workflowId\":\"wf\",\"status\":\"FAILED\",\"steps\":["
                        + "{\"stepId\":\"s1\",\"stepType\":\"ASSIGN\",\"status\":\"SUCCEEDED\","
                        + "\"condition\":null,\"outputVariableName\":\"o1\",\"failure\":null,\"action\":null},"
                        + "{\"stepId\":\"s2\",\"stepType\":\"ACTION\",\"status\":\"FAILED\","
                        + "\"condition\":null,\"outputVariableName\":null,\"failure\":{\"type\":\"ACTION_FAILED\","
                        + "\"safeMessage\":\"x\",\"stepId\":\"s2\",\"underlyingTypeName\":null,"
                        + "\"actionFailureType\":null},\"action\":null},"
                        + "{\"stepId\":\"s3\",\"stepType\":\"ACTION\",\"status\":\"NOT_RUN\","
                        + "\"condition\":null,\"outputVariableName\":null,\"failure\":null,\"action\":null}]},"
                        + "\"failure\":{\"type\":\"ACTION_FAILED\",\"safeMessage\":\"x\",\"stepId\":\"s2\","
                        + "\"underlyingTypeName\":null,\"actionFailureType\":null}}";

        WorkflowRecording decoded = codec.decode(json);

        assertThat(decoded.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(decoded.steps())
                .extracting(RecordedWorkflowStep::status)
                .containsExactly(
                        WorkflowStepStatus.SUCCEEDED,
                        WorkflowStepStatus.FAILED,
                        WorkflowStepStatus.NOT_RUN);
    }
}

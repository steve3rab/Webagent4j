package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.workflow.WorkflowFailureType;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonWorkflowRecordingCodecTest {

    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();

    /** JSON-001: encoding the same recording twice produces byte-identical output. */
    @Test
    void jsonDeterminismEncodingIsStable() {
        WorkflowRecording recording = RecordingFixtures.richRecording();

        String first = codec.encode(recording);
        String second = codec.encode(recording);

        assertThat(first).isEqualTo(second);
    }

    /** JSON-002: encode then decode reproduces an equal recording. */
    @Test
    void jsonRoundTripPreservesEveryField() {
        WorkflowRecording recording = RecordingFixtures.richRecording();

        WorkflowRecording decoded = codec.decode(codec.encode(recording));

        assertThat(decoded).isEqualTo(recording);
    }

    /** JSON-003: re-encoding a decoded recording reproduces the exact original canonical text. */
    @Test
    void jsonCanonicalReencodeIsByteIdentical() {
        WorkflowRecording recording = RecordingFixtures.richRecording();
        String encoded = codec.encode(recording);

        String reencoded = codec.encode(codec.decode(encoded));

        assertThat(reencoded).isEqualTo(encoded);
    }

    /** Encoding never pretty-prints and never appends a trailing newline. */
    @Test
    void encodingHasNoPrettyPrintingOrTrailingNewline() {
        String encoded = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));

        assertThat(encoded).doesNotContain("\n").doesNotContain("  ");
        assertThat(encoded).doesNotEndWith("\n");
    }

    /**
     * A valid preflight-style FAILED recording (overall failure has no stepId): every step is
     * NOT_RUN, satisfying {@link RecordingInvariants}, while still exercising a step whose optional
     * fields are all absent.
     */
    private static WorkflowRecording notRunOnlyRecording() {
        return RecordingFixtures.minimalFailed(
                "wf",
                List.of(RecordingFixtures.notRunStep("s1")),
                RecordingFixtures.failure(WorkflowFailureType.MISSING_REQUIRED_INPUT, null));
    }

    /** Every optional field is always emitted, as null when absent - never omitted. */
    @Test
    void absentOptionalsAreAlwaysEmittedAsNull() {
        String encoded = codec.encode(notRunOnlyRecording());

        assertThat(encoded)
                .contains("\"condition\":null")
                .contains("\"outputVariableName\":null")
                .contains("\"failure\":null")
                .contains("\"action\":null");
    }

    /** Enums are encoded by name, never by ordinal. */
    @Test
    void enumsAreEncodedByNameNeverOrdinal() {
        String encoded = codec.encode(notRunOnlyRecording());

        assertThat(encoded)
                .contains("\"stepType\":\"ACTION\"")
                .contains("\"status\":\"NOT_RUN\"")
                .contains("\"type\":\"MISSING_REQUIRED_INPUT\"");
    }

    /** JSON-004: an unsupported schemaVersion is rejected, with no fallback decoding. */
    @Test
    void jsonUnknownSchemaVersionIsRejected() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted = valid.replace("\"schemaVersion\":1", "\"schemaVersion\":999");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    /** JSON-005: a duplicate JSON object key is rejected, at any nesting level. */
    @Test
    void jsonDuplicateTopLevelFieldIsRejected() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted = valid.replaceFirst("\\{", "{\"schemaVersion\":1,");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void jsonDuplicateNestedFieldIsRejected() {
        String valid =
                codec.encode(
                        RecordingFixtures.minimalCompleted(
                                "wf", List.of(RecordingFixtures.succeededAssignStep("s1", "out"))));
        String corrupted =
                valid.replace("\"stepId\":\"s1\"", "\"stepId\":\"s1\",\"stepId\":\"s1\"");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    /** JSON-006: malformed JSON is rejected deterministically, without echoing the raw input. */
    @Test
    void jsonMalformedInputIsRejectedWithoutEchoingIt() {
        assertThatThrownBy(() -> codec.decode("{not valid json"))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageNotContaining("not valid json");
    }

    /** JSON-007: an invalid/unknown enum value is rejected. */
    @Test
    void jsonUnknownEnumValueIsRejected() {
        String valid =
                codec.encode(
                        RecordingFixtures.minimalCompleted(
                                "wf", List.of(RecordingFixtures.succeededAssignStep("s1", "out"))));
        String corrupted = valid.replace("\"status\":\"SUCCEEDED\"", "\"status\":\"BOGUS_STATUS\"");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageNotContaining("BOGUS_STATUS");
    }

    /** JSON-008: an impossible step-result combination is rejected as an invariant violation. */
    @Test
    void jsonInvariantViolationIsRejected() {
        String valid =
                codec.encode(
                        RecordingFixtures.minimalCompleted(
                                "wf", List.of(RecordingFixtures.succeededAssignStep("s1", "out"))));
        // Claims SKIPPED without ever supplying a condition - RecordedWorkflowStep forbids this.
        String corrupted = valid.replace("\"status\":\"SUCCEEDED\"", "\"status\":\"SKIPPED\"");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    /** JSON-009: content after the JSON document is rejected. */
    @Test
    void jsonTrailingDocumentIsRejected() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted = valid + "{}";

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void missingRequiredFieldIsRejected() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted = valid.replace("\"recordingId\":\"recording-1\",", "");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void unknownFieldIsRejected() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted = valid.substring(0, valid.length() - 1) + ",\"bogus\":1}";

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void wrongJsonTypeIsRejected() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted = valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void malformedInstantIsRejected() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted = valid.replace("\"2026-01-01T00:00:00Z\"", "\"not-a-timestamp\"");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageNotContaining("not-a-timestamp");
    }

    @Test
    void decodeNullInputRejected() {
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(NullPointerException.class);
    }
}

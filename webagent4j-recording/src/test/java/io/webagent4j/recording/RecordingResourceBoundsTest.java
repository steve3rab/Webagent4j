package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * REC-BOUND-001: proves {@link JsonWorkflowRecordingCodec#decode} bounds every resource-consuming
 * step of decoding a caller-supplied recording - overall document size, JSON nesting depth, string
 * length, field-name length, numeric-token length, and step count - each rejected deterministically
 * with {@link RecordingFormatException} before the allocation it protects, rather than depending on
 * available JVM heap or Jackson's own defaults.
 *
 * <p>Fixtures stay small and cheap even when proving a boundary near {@link
 * JsonWorkflowRecordingCodec#MAX_ENCODED_LENGTH_CHARS}: padding to an exact target length uses
 * inert leading whitespace (valid anywhere before a JSON document's first token, and never counted
 * as nesting, a string, or a field name), not a huge domain object.
 */
class RecordingResourceBoundsTest {

    private static final String DIAGNOSTIC_SENTINEL = "DIAGNOSTIC_SENTINEL_739152";

    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();

    // ---- fixture helpers ----

    private static List<RecordedWorkflowStep> minimalSteps(int count) {
        List<RecordedWorkflowStep> steps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            steps.add(RecordingFixtures.succeededAssignStep("s" + i, "o" + i));
        }
        return steps;
    }

    private static String recordingWithStepCount(int count) {
        WorkflowRecording recording = RecordingFixtures.minimalCompleted("wf", minimalSteps(count));
        return new JsonWorkflowRecordingCodec().encode(recording);
    }

    /**
     * Builds a {@code steps} array with {@code count} entries via direct JSON text duplication
     * rather than through {@link JsonWorkflowRecordingCodec#encode}: since REC-BOUND-001-followup,
     * {@code encode()} itself refuses a recording with more than {@code MAX_STEPS} steps (see
     * {@code RecordingCodecRoundTripBoundsTest}), so a decode-only test proving {@code decode()}
     * still independently rejects such a document - as it must for input from any other encoder -
     * needs a way to construct one that does not go through this codec's own {@code encode()}. Safe
     * only because the minimal fixture step contains no {@code [} or {@code ]} in any field value.
     */
    private static String recordingJsonWithStepCountViaCorruption(
            JsonWorkflowRecordingCodec codec, int count) {
        String oneStepDoc =
                codec.encode(
                        RecordingFixtures.minimalCompleted(
                                "wf", List.of(RecordingFixtures.succeededAssignStep("s0", "o0"))));
        int arrayFieldStart = oneStepDoc.indexOf("\"steps\":[");
        int arrayContentStart = arrayFieldStart + "\"steps\":[".length();
        int arrayEnd = oneStepDoc.indexOf(']', arrayContentStart);
        String stepJson = oneStepDoc.substring(arrayContentStart, arrayEnd);
        StringBuilder stepsArray = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                stepsArray.append(',');
            }
            stepsArray.append(stepJson);
        }
        stepsArray.append(']');
        return oneStepDoc.substring(0, arrayFieldStart)
                + "\"steps\":"
                + stepsArray
                + oneStepDoc.substring(arrayEnd + 1);
    }

    /** Pads {@code validJson} to exactly {@code targetLength} chars with leading whitespace. */
    private static String padToLength(String validJson, int targetLength) {
        int currentLength = validJson.length();
        if (targetLength < currentLength) {
            throw new IllegalArgumentException("target shorter than payload");
        }
        return " ".repeat(targetLength - currentLength) + validJson;
    }

    // ---- REC-LIMIT-001: an ordinary recording still decodes ----

    @Test
    void recLimit001NormalRecordingStillDecodes() {
        WorkflowRecording recording = RecordingFixtures.richRecording();

        WorkflowRecording decoded = codec.decode(codec.encode(recording));

        assertThat(decoded).isEqualTo(recording);
    }

    // ---- REC-LIMIT-002/003: total encoded size boundary ----

    @Test
    void recLimit002ExactlyAtTotalSizeBoundaryIsAccepted() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf"));
        String padded = padToLength(valid, JsonWorkflowRecordingCodec.MAX_ENCODED_LENGTH_CHARS);
        assertThat(padded).hasSize(JsonWorkflowRecordingCodec.MAX_ENCODED_LENGTH_CHARS);

        WorkflowRecording decoded = codec.decode(padded);

        assertThat(decoded).isEqualTo(RecordingFixtures.minimalCompleted("wf"));
    }

    @Test
    void recLimit003OneCharOverTotalSizeBoundaryIsRejected() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf"));
        String padded = padToLength(valid, JsonWorkflowRecordingCodec.MAX_ENCODED_LENGTH_CHARS + 1);

        assertThatThrownBy(() -> codec.decode(padded))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds maximum encoded size");
    }

    @Test
    void recLimit004VeryLargeInputIsRejectedSafelyAndCheaply() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf"));
        String huge = padToLength(valid, JsonWorkflowRecordingCodec.MAX_ENCODED_LENGTH_CHARS * 8);

        assertThatThrownBy(() -> codec.decode(huge))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds maximum encoded size");
    }

    // ---- REC-LIMIT-005/006: step count boundary ----

    @Test
    void recLimit005StepCountAtMaximumIsAcceptedWithinTheSizeBudget() {
        String encoded = recordingWithStepCount(JsonWorkflowRecordingCodec.MAX_STEPS);
        assertThat(encoded.length())
                .isLessThan(JsonWorkflowRecordingCodec.MAX_ENCODED_LENGTH_CHARS);

        WorkflowRecording decoded = codec.decode(encoded);

        assertThat(decoded.steps()).hasSize(JsonWorkflowRecordingCodec.MAX_STEPS);
    }

    @Test
    void recLimit006StepCountOverMaximumIsRejectedBeforeDomainConstruction() {
        String encoded =
                recordingJsonWithStepCountViaCorruption(
                        codec, JsonWorkflowRecordingCodec.MAX_STEPS + 1);
        // Confirms this fixture still exercises the step-count layer, not the size layer.
        assertThat(encoded.length())
                .isLessThan(JsonWorkflowRecordingCodec.MAX_ENCODED_LENGTH_CHARS);

        assertThatThrownBy(() -> codec.decode(encoded))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds maximum step count");
    }

    // ---- REC-LIMIT-007/008: string-length boundary ----

    @Test
    void recLimit007StringExactlyAtMaximumLengthIsAccepted() {
        String longValue = "v".repeat(JsonWorkflowRecordingCodec.MAX_STRING_LENGTH_CHARS);
        WorkflowRecording recording =
                RecordingFixtures.minimalCompleted(
                        "wf", List.of(RecordingFixtures.succeededAssignStep("s1", longValue)));

        WorkflowRecording decoded = codec.decode(codec.encode(recording));

        assertThat(decoded.steps().getFirst().outputVariableName()).contains(longValue);
    }

    @Test
    void recLimit008StringOneCharOverMaximumLengthIsRejected() {
        // Since REC-BOUND-001-followup, encode() itself now refuses to produce this string (see
        // RecordingCodecRoundTripBoundsTest), so this decode-only test builds the oversized JSON
        // directly - proving decode() still rejects it independently, e.g. from any other encoder.
        String tooLong = "v".repeat(JsonWorkflowRecordingCodec.MAX_STRING_LENGTH_CHARS + 1);
        String valid =
                codec.encode(
                        RecordingFixtures.minimalCompleted(
                                "wf", List.of(RecordingFixtures.succeededAssignStep("s1", "o"))));
        String corrupted =
                valid.replace(
                        "\"outputVariableName\":\"o\"",
                        "\"outputVariableName\":\"" + tooLong + "\"");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds a configured JSON resource limit");
    }

    // ---- REC-LIMIT-009/010: nesting-depth boundary ----

    private static String nestedArrayDocument(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append('[');
        }
        sb.append('1');
        for (int i = 0; i < depth; i++) {
            sb.append(']');
        }
        return sb.toString();
    }

    @Test
    void recLimit009ExcessiveNestingIsRejectedAsResourceLimit() {
        String deeplyNested =
                nestedArrayDocument(JsonWorkflowRecordingCodec.MAX_NESTING_DEPTH + 50);

        assertThatThrownBy(() -> codec.decode(deeplyNested))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds a configured JSON resource limit");
    }

    @Test
    void recLimit010NestingDepthBoundary() {
        String belowLimit = nestedArrayDocument(JsonWorkflowRecordingCodec.MAX_NESTING_DEPTH - 1);
        String atLimit = nestedArrayDocument(JsonWorkflowRecordingCodec.MAX_NESTING_DEPTH);
        String overLimit = nestedArrayDocument(JsonWorkflowRecordingCodec.MAX_NESTING_DEPTH + 1);

        // Below and at the limit: parsing itself succeeds, so rejection (if any) comes from schema
        // validation - not the nesting-depth resource check.
        assertThatThrownBy(() -> codec.decode(belowLimit))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageNotContaining("exceeds a configured JSON resource limit");
        assertThatThrownBy(() -> codec.decode(atLimit))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageNotContaining("exceeds a configured JSON resource limit");

        // One level past the limit: rejected specifically by the nesting-depth resource check.
        assertThatThrownBy(() -> codec.decode(overLimit))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds a configured JSON resource limit");
    }

    // ---- REC-LIMIT-011: an unknown field's huge/deep value cannot bypass resource constraints
    // ----

    @Test
    void recLimit011HugeUnknownFieldValueCannotBypassResourceConstraints() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf"));
        String deepValue = nestedArrayDocument(JsonWorkflowRecordingCodec.MAX_NESTING_DEPTH + 10);
        String corrupted = valid.substring(0, valid.length() - 1) + ",\"bogus\":" + deepValue + "}";

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds a configured JSON resource limit");
    }

    // ---- REC-LIMIT-012: a huge field name is rejected safely ----

    @Test
    void recLimit012HugeFieldNameIsRejectedSafely() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf"));
        String hugeName = "b".repeat(JsonWorkflowRecordingCodec.MAX_NAME_LENGTH_CHARS + 1);
        String corrupted = valid.substring(0, valid.length() - 1) + ",\"" + hugeName + "\":1}";

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds a configured JSON resource limit");
    }

    // ---- REC-LIMIT-013: a huge numeric token cannot create uncontrolled parser work ----

    @Test
    void recLimit013HugeNumericTokenIsRejectedSafely() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf"));
        String hugeNumber = "9".repeat(JsonWorkflowRecordingCodec.MAX_NUMBER_LENGTH_DIGITS + 1);
        String corrupted = valid.replace("\"schemaVersion\":1", "\"schemaVersion\":" + hugeNumber);

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class)
                .hasMessageContaining("exceeds a configured JSON resource limit");
    }

    // ---- REC-LIMIT-014/015/016: unaffected pre-existing strictness ----

    @Test
    void recLimit014DuplicateKeyBehaviorIsUnchanged() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf"));
        String corrupted = valid.replaceFirst("\\{", "{\"schemaVersion\":1,");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void recLimit015UnknownFieldBehaviorRemainsStrict() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf"));
        String corrupted = valid.substring(0, valid.length() - 1) + ",\"bogus\":1}";

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    @Test
    void recLimit016MalformedJsonMapsToTheSameSafeFailure() {
        assertThatThrownBy(() -> codec.decode("{not valid json"))
                .isInstanceOf(RecordingFormatException.class);
    }

    // ---- REC-LIMIT-017: no secret ever appears in a resource-limit diagnostic ----

    @Test
    void recLimit017NoDiagnosticSentinelInResourceLimitDiagnostics() {
        // Built by direct JSON corruption, not codec.encode(...): since REC-BOUND-001-followup,
        // encode() itself refuses to produce an oversized string (see
        // RecordingCodecRoundTripBoundsTest#roundtripBound005), so this decode-only safety property
        // is proven against a document decode() could still receive from any other encoder.
        String oversized =
                DIAGNOSTIC_SENTINEL
                        + "v".repeat(JsonWorkflowRecordingCodec.MAX_STRING_LENGTH_CHARS);
        String valid =
                codec.encode(
                        RecordingFixtures.minimalCompleted(
                                "wf", List.of(RecordingFixtures.succeededAssignStep("s1", "o"))));
        String encoded =
                valid.replace(
                        "\"outputVariableName\":\"o\"",
                        "\"outputVariableName\":\"" + oversized + "\"");

        RecordingFormatException exception =
                (RecordingFormatException)
                        assertThatThrownBy(() -> codec.decode(encoded))
                                .isInstanceOf(RecordingFormatException.class)
                                .actual();

        assertThat(exception.getMessage()).doesNotContain(DIAGNOSTIC_SENTINEL);
        assertThat(exception.toString()).doesNotContain(DIAGNOSTIC_SENTINEL);
        assertThat(exception.getCause()).isNull();
    }

    // ---- REC-LIMIT-018: deterministic, repeatable rejection with no state leak ----

    @Test
    void recLimit018RepeatedDecodeOfTheSameExcessiveFixtureFailsIdentically() {
        String encoded =
                recordingJsonWithStepCountViaCorruption(
                        codec, JsonWorkflowRecordingCodec.MAX_STEPS + 1);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> codec.decode(encoded))
                    .isInstanceOf(RecordingFormatException.class)
                    .hasMessageContaining("exceeds maximum step count");
        }

        // A normal recording still decodes correctly afterward - no limit state leaked between
        // calls on the same shared codec/factory.
        WorkflowRecording recording = RecordingFixtures.richRecording();
        WorkflowRecording decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    // ---- REC-LIMIT-019/020: encode/decode compatibility, including near the chosen bounds ----

    @Test
    void recLimit019RoundTripPreservesARepresentativeRecording() {
        WorkflowRecording recording = RecordingFixtures.richRecording();

        assertThat(codec.decode(codec.encode(recording))).isEqualTo(recording);
    }

    @Test
    void recLimit020BoundaryRoundTripAtMaximumStepCountSucceeds() {
        WorkflowRecording recording =
                RecordingFixtures.minimalCompleted(
                        "wf", minimalSteps(JsonWorkflowRecordingCodec.MAX_STEPS));
        String encoded = codec.encode(recording);
        assertThat(encoded.length())
                .isLessThan(JsonWorkflowRecordingCodec.MAX_ENCODED_LENGTH_CHARS);

        WorkflowRecording decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(recording);
    }
}

package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * REC-BOUND-001-followup: proves {@link JsonWorkflowRecordingCodec#encode} refuses to produce a
 * document its own {@link JsonWorkflowRecordingCodec#decode} would reject for a resource-bound
 * reason - restoring {@code decode(encode(recording))} coherence for every {@code
 * WorkflowRecording} the public domain model allows but this codec cannot represent within its own
 * limits.
 *
 * <p>Decode-side enforcement (added by REC-BOUND-001, PR #97) is unchanged; see {@link
 * RecordingResourceBoundsTest}. This class only adds encode-side prevalidation.
 */
class RecordingCodecRoundTripBoundsTest {

    private static final String DIAGNOSTIC_SENTINEL = "DIAGNOSTIC_SENTINEL_402917";

    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();

    private static List<RecordedWorkflowStep> minimalSteps(int count) {
        List<RecordedWorkflowStep> steps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            steps.add(RecordingFixtures.succeededAssignStep("s" + i, "o" + i));
        }
        return steps;
    }

    // ---- ROUNDTRIP-BOUND-001: a normal recording round-trips ----

    @Test
    void roundtripBound001NormalRecordingRoundTrips() {
        WorkflowRecording recording = RecordingFixtures.richRecording();

        WorkflowRecording decoded = codec.decode(codec.encode(recording));

        assertThat(decoded).isEqualTo(recording);
    }

    // ---- ROUNDTRIP-BOUND-002/003: step-count boundary ----

    @Test
    void roundtripBound002ExactlyMaxStepsEncodesAndDecodesSuccessfully() {
        WorkflowRecording recording =
                RecordingFixtures.minimalCompleted(
                        "wf", minimalSteps(JsonWorkflowRecordingCodec.MAX_STEPS));

        String encoded = codec.encode(recording);
        WorkflowRecording decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(recording);
        assertThat(decoded.steps()).hasSize(JsonWorkflowRecordingCodec.MAX_STEPS);
    }

    @Test
    void roundtripBound003OneStepOverMaxIsRejectedByEncodeItself() {
        WorkflowRecording recording =
                RecordingFixtures.minimalCompleted(
                        "wf", minimalSteps(JsonWorkflowRecordingCodec.MAX_STEPS + 1));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.encode(recording))
                .withMessageContaining("exceeds maximum encodable step count");
    }

    // ---- ROUNDTRIP-BOUND-004/005: string-length boundary ----

    @Test
    void roundtripBound004StringExactlyAtLimitEncodesAndDecodesSuccessfully() {
        String maxLengthValue = "v".repeat(JsonWorkflowRecordingCodec.MAX_STRING_LENGTH_CHARS);
        WorkflowRecording recording =
                RecordingFixtures.minimalCompleted(
                        "wf", List.of(RecordingFixtures.succeededAssignStep("s1", maxLengthValue)));

        String encoded = codec.encode(recording);
        WorkflowRecording decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(recording);
    }

    @Test
    void roundtripBound005StringOneCharOverLimitIsRejectedByEncodeItself() {
        String tooLong = "v".repeat(JsonWorkflowRecordingCodec.MAX_STRING_LENGTH_CHARS + 1);
        WorkflowRecording recording =
                RecordingFixtures.minimalCompleted(
                        "wf", List.of(RecordingFixtures.succeededAssignStep("s1", tooLong)));

        assertThatThrownBy(() -> codec.encode(recording))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeding the codec limit")
                .hasMessageNotContaining(tooLong);
    }

    // ---- ROUNDTRIP-BOUND-006/007: total encoded-size boundary ----

    /**
     * A recording near {@code MAX_ENCODED_LENGTH_CHARS} built from many moderate-length steps -
     * individually within {@code MAX_STEPS} and {@code MAX_STRING_LENGTH_CHARS} - so the only limit
     * this fixture can hit is the total-document one.
     */
    private static WorkflowRecording nearTotalSizeLimitRecording(int stepCount, int valueLength) {
        List<RecordedWorkflowStep> steps = new ArrayList<>(stepCount);
        String value = "v".repeat(valueLength);
        for (int i = 0; i < stepCount; i++) {
            steps.add(RecordingFixtures.succeededAssignStep("s" + i, value));
        }
        return RecordingFixtures.minimalCompleted("wf", steps);
    }

    @Test
    void roundtripBound006RecordingAtOrBelowTotalSizeLimitEncodesAndDecodesSuccessfully() {
        // 100 steps * ~2000 chars/step comfortably clears MAX_STEPS (1,000) and
        // MAX_STRING_LENGTH_CHARS (32,768) individually, while still landing safely under
        // MAX_ENCODED_LENGTH_CHARS (262,144) as a whole - a deterministic fixture, not fragile
        // hand-counting to an exact boundary.
        WorkflowRecording recording = nearTotalSizeLimitRecording(100, 2_000);

        String encoded = codec.encode(recording);

        assertThat(encoded.length())
                .isLessThan(JsonWorkflowRecordingCodec.MAX_ENCODED_LENGTH_CHARS);
        assertThat(codec.decode(encoded)).isEqualTo(recording);
    }

    @Test
    void roundtripBound007TotalSizeOverLimitIsRejectedEvenWithEveryIndividualValueInBounds() {
        // Each step's stepId/outputVariableName individually stays under MAX_STRING_LENGTH_CHARS,
        // and the step count individually stays under MAX_STEPS, but 200 steps of ~2,000 chars each
        // sums past MAX_ENCODED_LENGTH_CHARS (262,144) - proving the total-size check catches what
        // the per-step and per-string checks alone cannot.
        WorkflowRecording recording = nearTotalSizeLimitRecording(200, 2_000);
        assertThat(recording.steps()).hasSizeLessThan(JsonWorkflowRecordingCodec.MAX_STEPS);

        assertThatThrownBy(() -> codec.encode(recording))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum supported size");
    }

    // ---- ROUNDTRIP-BOUND-008: no secret leakage in an encode-side rejection ----

    @Test
    void roundtripBound008EncodeRejectionNeverLeaksTheOversizedValue() {
        String oversized =
                DIAGNOSTIC_SENTINEL
                        + "v".repeat(JsonWorkflowRecordingCodec.MAX_STRING_LENGTH_CHARS);
        WorkflowRecording recording =
                RecordingFixtures.minimalCompleted(
                        "wf", List.of(RecordingFixtures.succeededAssignStep("s1", oversized)));

        IllegalArgumentException exception =
                (IllegalArgumentException)
                        assertThatThrownBy(() -> codec.encode(recording))
                                .isInstanceOf(IllegalArgumentException.class)
                                .actual();

        assertThat(exception.getMessage()).doesNotContain(DIAGNOSTIC_SENTINEL);
        assertThat(exception.toString()).doesNotContain(DIAGNOSTIC_SENTINEL);
        assertThat(exception.getCause()).isNull();
    }

    // ---- ROUNDTRIP-BOUND-009: deterministic repeated rejection, no shared-state leak ----

    @Test
    void roundtripBound009RepeatedEncodeOfTheSameOversizedRecordingFailsIdentically() {
        WorkflowRecording oversized =
                RecordingFixtures.minimalCompleted(
                        "wf", minimalSteps(JsonWorkflowRecordingCodec.MAX_STEPS + 1));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> codec.encode(oversized))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum encodable step count");
        }
    }

    // ---- ROUNDTRIP-BOUND-010: a normal call still succeeds right after a failed one ----

    @Test
    void roundtripBound010SuccessfulCallAfterFailedCallProvesNoCorruptedState() {
        WorkflowRecording oversized =
                RecordingFixtures.minimalCompleted(
                        "wf", minimalSteps(JsonWorkflowRecordingCodec.MAX_STEPS + 1));
        assertThatThrownBy(() -> codec.encode(oversized))
                .isInstanceOf(IllegalArgumentException.class);

        WorkflowRecording normal = RecordingFixtures.richRecording();
        String encoded = codec.encode(normal);
        WorkflowRecording decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(normal);
    }

    // ---- canonical-output compatibility: this follow-up must not change output for in-bounds
    // recordings ----

    @Test
    void canonicalOutputForAnInBoundsRecordingIsUnchanged() {
        WorkflowRecording recording = RecordingFixtures.richRecording();

        String first = codec.encode(recording);
        String second = codec.encode(recording);

        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotContain("\n").doesNotContain("  ");
        assertThat(first).startsWith("{\"schemaVersion\":1,\"recordingId\":");
    }

    // ---- Section 14: every arbitrary caller-controlled string emitted by the encoder is bound
    // ----

    private static String longValue() {
        return "x".repeat(JsonWorkflowRecordingCodec.MAX_STRING_LENGTH_CHARS + 1);
    }

    /**
     * One entry per arbitrary (non-enum, non-fixed) string-bearing field the encoder emits, each
     * producing a {@code WorkflowRecording} whose only defect is that one field being one character
     * over {@link JsonWorkflowRecordingCodec#MAX_STRING_LENGTH_CHARS}. Proves the single shared
     * {@code requireEncodableLength} helper actually runs on every emission path, rather than
     * testing each path with a near-identical hand-written test.
     */
    static List<Function<String, WorkflowRecording>> oversizedFieldFixtures() {
        return List.of(
                // recordingId
                value ->
                        new WorkflowRecording(
                                RecordingSchemaVersion.V1,
                                new RecordingId(value),
                                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                                new io.webagent4j.workflow.WorkflowId("wf"),
                                io.webagent4j.workflow.WorkflowStatus.COMPLETED,
                                List.of(RecordingFixtures.succeededAssignStep("s1", "o")),
                                java.util.Optional.empty()),
                // workflowId
                value ->
                        new WorkflowRecording(
                                RecordingSchemaVersion.V1,
                                new RecordingId("r1"),
                                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                                new io.webagent4j.workflow.WorkflowId(value),
                                io.webagent4j.workflow.WorkflowStatus.COMPLETED,
                                List.of(RecordingFixtures.succeededAssignStep("s1", "o")),
                                java.util.Optional.empty()),
                // step stepId
                value ->
                        RecordingFixtures.minimalCompleted(
                                "wf", List.of(RecordingFixtures.succeededAssignStep(value, "o"))),
                // step outputVariableName
                value ->
                        RecordingFixtures.minimalCompleted(
                                "wf", List.of(RecordingFixtures.succeededAssignStep("s1", value))),
                // condition description
                value ->
                        RecordingFixtures.minimalCompleted(
                                "wf", List.of(RecordingFixtures.skippedStep("s1", false, value))),
                // failure.safeMessage (overall + step failure, via a preflight failure)
                value ->
                        RecordingFixtures.minimalFailed(
                                "wf",
                                List.of(RecordingFixtures.notRunStep("s1")),
                                new RecordedFailure(
                                        io.webagent4j.workflow.WorkflowFailureType
                                                .MISSING_REQUIRED_INPUT,
                                        value,
                                        java.util.Optional.empty(),
                                        java.util.Optional.empty(),
                                        java.util.Optional.empty())),
                // failure.underlyingTypeName (runtime failure, via a step exception)
                value ->
                        RecordingFixtures.minimalFailed(
                                "wf",
                                List.of(
                                        RecordingFixtures.actionStepFailedNoSummary(
                                                "s1",
                                                new RecordedFailure(
                                                        io.webagent4j.workflow.WorkflowFailureType
                                                                .STEP_EXCEPTION,
                                                        "safe message",
                                                        java.util.Optional.of(
                                                                new io.webagent4j.workflow
                                                                        .WorkflowStepId("s1")),
                                                        java.util.Optional.of(value),
                                                        java.util.Optional.empty()))),
                                new RecordedFailure(
                                        io.webagent4j.workflow.WorkflowFailureType.STEP_EXCEPTION,
                                        "safe message",
                                        java.util.Optional.of(
                                                new io.webagent4j.workflow.WorkflowStepId("s1")),
                                        java.util.Optional.of(value),
                                        java.util.Optional.empty())),
                // action.actionId
                value ->
                        RecordingFixtures.minimalCompleted(
                                "wf",
                                List.of(
                                        new RecordedWorkflowStep(
                                                new io.webagent4j.workflow.WorkflowStepId("s1"),
                                                io.webagent4j.workflow.WorkflowStepType.ACTION,
                                                io.webagent4j.workflow.WorkflowStepStatus.SUCCEEDED,
                                                java.util.Optional.empty(),
                                                java.util.Optional.of("out"),
                                                java.util.Optional.empty(),
                                                java.util.Optional.of(
                                                        new RecordedAction(
                                                                new io.webagent4j.action.ActionId(
                                                                        value),
                                                                io.webagent4j.action.ActionType
                                                                        .CLICK,
                                                                io.webagent4j.action.ActionStatus
                                                                        .SUCCESS,
                                                                io.webagent4j.action
                                                                        .ActionExecutionMode
                                                                        .REAL))))));
    }

    @ParameterizedTest
    @MethodSource("oversizedFieldFixtures")
    void everyArbitraryStringFieldIsBoundOnEncode(Function<String, WorkflowRecording> build) {
        WorkflowRecording oversized = build.apply(longValue());

        assertThatThrownBy(() -> codec.encode(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeding the codec limit");
    }

    /** Sanity check: the same fixtures at a valid length encode and decode successfully. */
    @ParameterizedTest
    @MethodSource("oversizedFieldFixtures")
    void everyArbitraryStringFieldRoundTripsAtAValidLength(
            Function<String, WorkflowRecording> build) {
        WorkflowRecording valid = build.apply("short-value");

        WorkflowRecording decoded = codec.decode(codec.encode(valid));

        assertThat(decoded).isEqualTo(valid);
    }
}

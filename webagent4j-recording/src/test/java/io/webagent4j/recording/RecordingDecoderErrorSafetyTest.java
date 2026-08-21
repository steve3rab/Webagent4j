package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link JsonWorkflowRecordingCodec#decode} never republishes any part of the untrusted
 * external JSON it rejects: neither an unknown field's own name, an invalid enum's own text, a
 * malformed timestamp's own text, nor the raw Jackson parser exception ever reaches a thrown {@link
 * RecordingFormatException}'s message, {@code toString()}, or cause.
 *
 * <p>Sentinels are distinctive, made-up tokens (never a generic word like "secret" or "password")
 * so a false pass cannot hide behind a coincidental match against framework diagnostic text.
 */
class RecordingDecoderErrorSafetyTest {

    private static final String FIELD_SENTINEL = "WA4J_DECODER_SECRET_FIELD_918273";
    private static final String VALUE_SENTINEL = "WA4J_DECODER_SECRET_VALUE_812734";
    private static final String ENUM_SENTINEL = "WA4J_DECODER_SECRET_ENUM_192837";

    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();

    private String validSingleStepRecording() {
        return codec.encode(
                RecordingFixtures.minimalCompleted(
                        "wf", List.of(RecordingFixtures.succeededAssignStep("s1", "out"))));
    }

    /** ERR-SAFE-001: an unknown external field's own name is never echoed. */
    @Test
    void errSafe001UnknownFieldNameNeverEchoed() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted =
                valid.substring(0, valid.length() - 1) + ",\"" + FIELD_SENTINEL + "\":1}";

        RecordingFormatException exception =
                (RecordingFormatException)
                        assertThatThrownBy(() -> codec.decode(corrupted))
                                .isInstanceOf(RecordingFormatException.class)
                                .actual();

        assertThat(exception.getMessage()).doesNotContain(FIELD_SENTINEL);
        assertThat(exception.toString()).doesNotContain(FIELD_SENTINEL);
        assertThat(exception.getCause()).isNull();
    }

    /** ERR-SAFE-002: a sentinel embedded in malformed JSON is never echoed. */
    @Test
    void errSafe002MalformedTokenNeverEchoed() {
        String corrupted = "{ malformed " + VALUE_SENTINEL + " not json";

        RecordingFormatException exception =
                (RecordingFormatException)
                        assertThatThrownBy(() -> codec.decode(corrupted))
                                .isInstanceOf(RecordingFormatException.class)
                                .actual();

        assertThat(exception.getMessage()).doesNotContain(VALUE_SENTINEL);
        assertThat(exception.toString()).doesNotContain(VALUE_SENTINEL);
        assertThat(exception.getCause()).isNull();
    }

    /** ERR-SAFE-003: a duplicated external field's own sentinel name is never echoed. */
    @Test
    void errSafe003DuplicateExternalFieldNeverEchoed() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted =
                valid.replaceFirst(
                        "\\{", "{\"" + FIELD_SENTINEL + "\":1,\"" + FIELD_SENTINEL + "\":2,");

        RecordingFormatException exception =
                (RecordingFormatException)
                        assertThatThrownBy(() -> codec.decode(corrupted))
                                .isInstanceOf(RecordingFormatException.class)
                                .actual();

        assertThat(exception.getMessage()).doesNotContain(FIELD_SENTINEL);
        assertThat(exception.toString()).doesNotContain(FIELD_SENTINEL);
        assertThat(exception.getCause()).isNull();
    }

    /** ERR-SAFE-004: an invalid enum value's own sentinel text is never echoed. */
    @Test
    void errSafe004InvalidEnumValueNeverEchoed() {
        String valid = validSingleStepRecording();
        String corrupted =
                valid.replace("\"status\":\"SUCCEEDED\"", "\"status\":\"" + ENUM_SENTINEL + "\"");

        RecordingFormatException exception =
                (RecordingFormatException)
                        assertThatThrownBy(() -> codec.decode(corrupted))
                                .isInstanceOf(RecordingFormatException.class)
                                .actual();

        assertThat(exception.getMessage()).doesNotContain(ENUM_SENTINEL);
        assertThat(exception.toString()).doesNotContain(ENUM_SENTINEL);
        assertThat(exception.getCause()).isNull();
    }

    /** ERR-SAFE-005: a malformed Instant's own sentinel text is never echoed. */
    @Test
    void errSafe005MalformedInstantNeverEchoed() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted = valid.replace("\"2026-01-01T00:00:00Z\"", "\"" + VALUE_SENTINEL + "\"");

        RecordingFormatException exception =
                (RecordingFormatException)
                        assertThatThrownBy(() -> codec.decode(corrupted))
                                .isInstanceOf(RecordingFormatException.class)
                                .actual();

        assertThat(exception.getMessage()).doesNotContain(VALUE_SENTINEL);
        assertThat(exception.toString()).doesNotContain(VALUE_SENTINEL);
        assertThat(exception.getCause()).isNull();
    }

    /** ERR-SAFE-006: an unsupported schemaVersion's raw numeric value stays out of the message. */
    @Test
    void errSafe006UnsupportedSchemaVersionNeverEchoesRawValue() {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf", List.of()));
        String corrupted = valid.replace("\"schemaVersion\":1", "\"schemaVersion\":48271");

        RecordingFormatException exception =
                (RecordingFormatException)
                        assertThatThrownBy(() -> codec.decode(corrupted))
                                .isInstanceOf(RecordingFormatException.class)
                                .actual();

        assertThat(exception.getMessage()).doesNotContain("48271");
        assertThat(exception.getCause()).isNull();
    }

    /**
     * A recording invariant violation (external structural attack) never carries a cause either.
     */
    @Test
    void invariantViolationCauseIsNull() {
        String valid = validSingleStepRecording();
        String corrupted = valid.replace("\"status\":\"SUCCEEDED\"", "\"status\":\"SKIPPED\"");

        RecordingFormatException exception =
                (RecordingFormatException)
                        assertThatThrownBy(() -> codec.decode(corrupted))
                                .isInstanceOf(RecordingFormatException.class)
                                .actual();

        assertThat(exception.getCause()).isNull();
    }
}

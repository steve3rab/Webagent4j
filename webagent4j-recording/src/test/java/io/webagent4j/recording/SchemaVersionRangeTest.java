package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Decoder numeric-range strictness for {@code schemaVersion}: {@code JsonNode.intValue()} alone
 * truncates a value outside the signed 32-bit range (for example {@code 2^32 + 1} silently becomes
 * {@code 1}), which could otherwise smuggle an out-of-range value into an accidentally-accepted
 * {@link RecordingSchemaVersion#V1}. {@link JsonWorkflowRecordingCodec} must reject any {@code
 * schemaVersion} that is not exactly representable as a Java {@code int} before ever calling {@code
 * intValue()}.
 */
class SchemaVersionRangeTest {

    private final JsonWorkflowRecordingCodec codec = new JsonWorkflowRecordingCodec();

    private String validWithSchemaVersion(String rawSchemaVersion) {
        String valid = codec.encode(RecordingFixtures.minimalCompleted("wf"));
        return valid.replace("\"schemaVersion\":1", "\"schemaVersion\":" + rawSchemaVersion);
    }

    /** VERSION-RANGE-001: 2^32 + 1 (low 32 bits equal 1) must never decode as V1. */
    @Test
    void versionRange001HugePositiveOverflowIsRejected() {
        String corrupted = validWithSchemaVersion("4294967297");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    /** VERSION-RANGE-002: a huge negative value whose low 32 bits also equal 1 must be rejected. */
    @Test
    void versionRange002HugeNegativeOverflowIsRejected() {
        String corrupted = validWithSchemaVersion("-4294967295");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    /** VERSION-RANGE-003: an astronomically large positive BigInteger must be rejected. */
    @Test
    void versionRange003AstronomicalPositiveBigIntegerIsRejected() {
        String corrupted = validWithSchemaVersion("999999999999999999999999999999999999");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    /** VERSION-RANGE-004: an astronomically large negative BigInteger must be rejected. */
    @Test
    void versionRange004AstronomicalNegativeBigIntegerIsRejected() {
        String corrupted = validWithSchemaVersion("-999999999999999999999999999999999999");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    /** VERSION-RANGE-005: a floating-point JSON token must be rejected, even when whole-valued. */
    @Test
    void versionRange005FloatingPointTokenIsRejected() {
        String corrupted = validWithSchemaVersion("1.0");

        assertThatThrownBy(() -> codec.decode(corrupted))
                .isInstanceOf(RecordingFormatException.class);
    }

    /** VERSION-RANGE-006: the ordinary in-range value 1 decodes successfully as V1. */
    @Test
    void versionRange006OrdinaryValueDecodesAsV1() {
        String valid = validWithSchemaVersion("1");

        WorkflowRecording decoded = codec.decode(valid);

        assertThat(decoded.schemaVersion()).isEqualTo(RecordingSchemaVersion.V1);
    }
}

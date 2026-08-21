package io.webagent4j.recording;

/**
 * Thrown when a serialized recording cannot be decoded: malformed JSON, a duplicate or unknown
 * field, an unsupported schema version, an invalid enum value, a malformed {@code Instant}, a value
 * of the wrong JSON type, a violated recording invariant, or trailing content after the document.
 *
 * <p>Every message is a fixed, bounded, deterministic string that names only a schema field path
 * (for example {@code "$.workflow.steps[2].status"}) - it never echoes the offending raw value or
 * any slice of the source input, since the source of a malformed recording may itself carry a
 * secret value the caller intended to keep out of logs.
 */
public final class RecordingFormatException extends IllegalArgumentException {

    /** Creates an exception with a fixed, safe diagnostic message. */
    public RecordingFormatException(String message) {
        super(message);
    }

    /** Creates an exception with a fixed, safe diagnostic message and a cause. */
    public RecordingFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}

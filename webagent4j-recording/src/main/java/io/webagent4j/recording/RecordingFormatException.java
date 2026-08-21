package io.webagent4j.recording;

/**
 * Thrown when a serialized recording cannot be decoded: malformed JSON, a duplicate or unknown
 * field, an unsupported schema version, an invalid enum value, a malformed {@code Instant}, a value
 * of the wrong JSON type, a violated recording invariant, or trailing content after the document.
 *
 * <p>Every message is a fixed, framework-owned string that names only a schema field path (for
 * example {@code "$.workflow.steps[2].status"}) - it never echoes the offending raw value, an
 * unknown field's own name, or any slice of the source input, since the source of a malformed
 * recording may itself carry a secret value the caller intended to keep out of logs. {@link
 * #getCause()} is always {@code null}: {@link JsonWorkflowRecordingCodec#decode} never attaches the
 * underlying Jackson parser exception or any other exception carrying external data, since that
 * exception's own message can embed source snippets this type is meant to keep out of a public
 * diagnostic.
 *
 * <p>Both constructors are package-private: this type is primarily something a caller catches, not
 * constructs. A caller-supplied message could not honor the safety guarantee above, so only code
 * inside {@code io.webagent4j.recording} - which controls every message passed here - may create
 * one.
 */
public final class RecordingFormatException extends IllegalArgumentException {

    /** Creates an exception with a fixed, safe diagnostic message. */
    RecordingFormatException(String message) {
        super(message);
    }
}

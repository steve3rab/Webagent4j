package io.webagent4j.recording;

/**
 * Encodes a {@link WorkflowRecording} to a serialized transport form and decodes it back.
 *
 * <p>This is a trusted Java extension point: the only implementation Phase 0.9-A ships is {@link
 * JsonWorkflowRecordingCodec}, but a caller may implement this interface for an alternative
 * serialized form. {@code encode} is expected to be deterministic - the same recording always
 * produces the same output - and {@code decode} is expected to be strict, rejecting any input that
 * is not exactly a well-formed, current-schema-version encoding rather than guessing at intent.
 */
public interface IWorkflowRecordingCodec {

    /** Encodes {@code recording} to its serialized transport form. */
    String encode(WorkflowRecording recording);

    /**
     * Decodes a previously encoded recording.
     *
     * @throws RecordingFormatException if {@code data} is not a well-formed, current-schema-version
     *     encoding of a {@link WorkflowRecording}
     */
    WorkflowRecording decode(String data);
}

package io.webagent4j.recording;

/**
 * Encodes a {@link WorkflowRecordingV2} to a serialized transport form and decodes it back.
 *
 * <p>This is the Recording V2 counterpart of {@link IWorkflowRecordingCodec} - a separate
 * interface, not a generic or overloaded one, since {@link WorkflowRecordingV2} is a structurally
 * unrelated root type from {@link WorkflowRecording} and this module performs no implicit
 * conversion between the two. The only implementation is {@link JsonWorkflowRecordingV2Codec}, but
 * a caller may implement this interface for an alternative serialized form. {@code encode} is
 * expected to be deterministic - the same recording always produces the same output - and {@code
 * decode} is expected to be strict, rejecting any input that is not exactly a well-formed,
 * current-schema-version encoding rather than guessing at intent.
 */
public interface IWorkflowRecordingV2Codec {

    /** Encodes {@code recording} to its serialized transport form. */
    String encode(WorkflowRecordingV2 recording);

    /**
     * Decodes a previously encoded recording.
     *
     * @throws RecordingFormatException if {@code data} is not a well-formed, current-schema-version
     *     encoding of a {@link WorkflowRecordingV2}
     */
    WorkflowRecordingV2 decode(String data);
}

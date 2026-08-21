package io.webagent4j.recording;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of comparing a {@link WorkflowRecording} against a new {@code WorkflowResult}.
 *
 * @param recordingId the recording that was verified against
 * @param mismatches every difference found, in deterministic traversal order; empty when the actual
 *     execution matches the recording
 */
public record WorkflowReplayResult(
        RecordingId recordingId, List<WorkflowReplayMismatch> mismatches) {

    /** Validates and defensively copies result data. */
    public WorkflowReplayResult {
        Objects.requireNonNull(recordingId, "recordingId");
        mismatches = List.copyOf(Objects.requireNonNull(mismatches, "mismatches"));
    }

    /**
     * Returns whether the actual execution matched the recording, i.e. {@link #mismatches()} is
     * empty.
     */
    public boolean matches() {
        return mismatches.isEmpty();
    }
}

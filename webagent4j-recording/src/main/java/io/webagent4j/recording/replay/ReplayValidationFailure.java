package io.webagent4j.recording.replay;

import java.util.Objects;

/**
 * A structured, secret-free reason {@link ReplayValidator#validate} rejected a {@code
 * WorkflowRecordingV2}/{@code Workflow} pair for Deterministic Replay.
 *
 * <p>{@code safeMessage} is always a fixed, framework-owned literal - never interpolated with a
 * value from the recording or the workflow - matching this module's existing decoder-diagnostic
 * discipline (see {@code RecordingFormatException}'s Javadoc for the same rationale).
 *
 * @param type the structured failure category
 * @param safeMessage a fixed, human-readable diagnostic
 */
public record ReplayValidationFailure(ReplayFailureType type, String safeMessage) {

    /** Validates failure data. */
    public ReplayValidationFailure {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(safeMessage, "safeMessage");
    }
}

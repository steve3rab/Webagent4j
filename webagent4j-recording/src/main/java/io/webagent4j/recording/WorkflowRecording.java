package io.webagent4j.recording;

import io.webagent4j.workflow.WorkflowId;
import io.webagent4j.workflow.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, versioned, secret-safe recording of one workflow execution.
 *
 * <p>A recording is data, not a program: it has no {@code execute()} method, retains no action
 * factory, prepared action plan, page, or browser reference, and cannot replay itself. Replaying a
 * recording means asking {@link WorkflowReplayVerifier} to compare it against a caller-supplied
 * {@code WorkflowResult} from a new, independently performed execution - never deserializing this
 * type to automatically click, type, submit, or navigate again. See {@code docs/recording.md} for
 * the full architecture and the rationale for what is and is not recorded.
 *
 * <p>{@code recordingId} and {@code capturedAt} are always caller-supplied, exactly like {@code
 * WorkflowId}: neither is generated inside this module, so the same recording data always produces
 * the same identity and timestamp regardless of how many times it is captured or re-encoded.
 *
 * @param schemaVersion the canonical JSON schema version this recording conforms to
 * @param recordingId the caller-supplied recording identifier, ignored by {@link
 *     WorkflowReplayVerifier}
 * @param capturedAt the caller-supplied capture time, ignored by {@link WorkflowReplayVerifier}
 * @param workflowId the recorded execution's workflow identifier
 * @param status the recorded execution's overall terminal outcome
 * @param steps every step's recorded outcome, in workflow definition order
 * @param failure the overall recorded failure, present exactly when {@code status} is {@link
 *     WorkflowStatus#FAILED}
 */
public record WorkflowRecording(
        RecordingSchemaVersion schemaVersion,
        RecordingId recordingId,
        Instant capturedAt,
        WorkflowId workflowId,
        WorkflowStatus status,
        List<RecordedWorkflowStep> steps,
        Optional<RecordedFailure> failure) {

    /** Validates and defensively copies recording data. */
    public WorkflowRecording {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(recordingId, "recordingId");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(status, "status");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        failure = Objects.requireNonNull(failure, "failure");
        if (status == WorkflowStatus.FAILED && failure.isEmpty()) {
            throw new IllegalArgumentException("a FAILED recording must carry a failure");
        }
        if (status != WorkflowStatus.FAILED && failure.isPresent()) {
            throw new IllegalArgumentException("only a FAILED recording may carry a failure");
        }
    }
}

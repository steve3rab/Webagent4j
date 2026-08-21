package io.webagent4j.recording;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.workflow.WorkflowFailureType;
import io.webagent4j.workflow.WorkflowStepId;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe, recorded failure reason, used both for the overall {@link WorkflowRecording#failure()} and
 * for a per-step {@link RecordedWorkflowStep#failure()}.
 *
 * <p>Mirrors {@code WorkflowFailure} field-for-field: {@code safeMessage} and {@code
 * underlyingTypeName} are retained only for diagnostics and are never compared by {@link
 * WorkflowReplayVerifier} - a message can legitimately differ in incidental detail (for example an
 * embedded timestamp or byte offset) between two semantically identical executions, and the
 * underlying exception's class name is an implementation detail, not part of the workflow's
 * documented failure contract.
 *
 * @param type stable failure category
 * @param safeMessage redacted, human-readable diagnostic, not compared during replay
 * @param stepId the step that caused this failure, if execution reached one
 * @param underlyingTypeName the failing exception's class name, if one was thrown; not compared
 *     during replay
 * @param actionFailureType the projected {@link ActionFailureType}, for an {@code ACTION_FAILED}
 *     failure
 */
public record RecordedFailure(
        WorkflowFailureType type,
        String safeMessage,
        Optional<WorkflowStepId> stepId,
        Optional<String> underlyingTypeName,
        Optional<ActionFailureType> actionFailureType) {

    /**
     * Validates failure data, including the {@code ActionFailureType} taxonomy: {@code
     * ActionWorkflowStep} only ever projects an {@code ActionFailureType} for {@code ACTION_FAILED}
     * (from a non-success {@code ActionResult}, which always carries one), and never for any other
     * {@code WorkflowFailureType}.
     */
    public RecordedFailure {
        Objects.requireNonNull(type, "type");
        safeMessage = Objects.requireNonNull(safeMessage, "safeMessage");
        stepId = Objects.requireNonNull(stepId, "stepId");
        underlyingTypeName = Objects.requireNonNull(underlyingTypeName, "underlyingTypeName");
        actionFailureType = Objects.requireNonNull(actionFailureType, "actionFailureType");
        if (type == WorkflowFailureType.ACTION_FAILED) {
            if (actionFailureType.isEmpty()) {
                throw new IllegalArgumentException(
                        "an ACTION_FAILED failure must carry an ActionFailureType");
            }
        } else if (actionFailureType.isPresent()) {
            throw new IllegalArgumentException(
                    "only an ACTION_FAILED failure may carry an ActionFailureType");
        }
    }
}

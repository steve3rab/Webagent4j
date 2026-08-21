package io.webagent4j.workflow;

import io.webagent4j.action.ActionFailureType;
import java.util.Objects;
import java.util.Optional;

/**
 * Safe, structured overall failure reason for a {@link WorkflowStatus#FAILED} {@link
 * WorkflowResult}.
 *
 * <p>This deliberately never exposes an arbitrary raw {@link Throwable}: a step's own thrown
 * exception could carry a secret value in its message (for example {@code new RuntimeException("bad
 * credential " + password)}), and returning it here would let a caller bypass every masking
 * guarantee simply by calling {@code getMessage()}. {@link #safeMessage()} is always already
 * redacted (see {@code docs/workflow.md#secret-masking}); at most {@link #underlyingTypeName()} -
 * the thrown exception's class name, never its message or stack trace - is retained.
 *
 * @param type stable failure category
 * @param safeMessage redacted, human-readable diagnostic
 * @param stepId the step that caused this failure, if execution reached one
 * @param underlyingTypeName the failing exception's class name, if one was thrown
 * @param actionFailureType the projected {@link ActionFailureType}, for an {@link
 *     WorkflowFailureType#ACTION_FAILED} failure
 */
public record WorkflowFailure(
        WorkflowFailureType type,
        String safeMessage,
        Optional<WorkflowStepId> stepId,
        Optional<String> underlyingTypeName,
        Optional<ActionFailureType> actionFailureType) {

    /** Validates failure data. */
    public WorkflowFailure {
        Objects.requireNonNull(type, "type");
        safeMessage = Objects.requireNonNull(safeMessage, "safeMessage");
        stepId = Objects.requireNonNull(stepId, "stepId");
        underlyingTypeName = Objects.requireNonNull(underlyingTypeName, "underlyingTypeName");
        actionFailureType = Objects.requireNonNull(actionFailureType, "actionFailureType");
    }

    /** Renders only the safe category, step, and redacted message - never a raw exception. */
    @Override
    public String toString() {
        return "WorkflowFailure[type="
                + type
                + ", step="
                + stepId.map(WorkflowStepId::value).orElse("-")
                + ", message="
                + safeMessage
                + "]";
    }
}

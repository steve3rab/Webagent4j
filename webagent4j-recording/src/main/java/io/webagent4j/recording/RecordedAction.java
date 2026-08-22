package io.webagent4j.recording;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import java.util.Objects;

/**
 * Recorded projection of one action-backed step's outcome.
 *
 * <p>Mirrors {@code WorkflowActionSummary} field-for-field. The action type, status, and execution
 * mode are categorical, and no raw action result value is retained. {@code actionId} is opaque
 * metadata supplied by the action pipeline: this module persists it verbatim and does not
 * secret-redact it. {@link WorkflowReplayVerifier} ignores it because correlation identity is not a
 * semantic workflow outcome.
 *
 * @param actionId the action pipeline's correlation identifier, persisted verbatim and expected to
 *     contain non-sensitive metadata
 * @param actionType the executed operation category
 * @param status the action pipeline's terminal status
 * @param executionMode whether the backend was actually invoked, simulated, or never reached
 */
public record RecordedAction(
        ActionId actionId,
        ActionType actionType,
        ActionStatus status,
        ActionExecutionMode executionMode) {

    /** Validates action data. */
    public RecordedAction {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(executionMode, "executionMode");
        requireExecutionShape(status, executionMode);
    }

    private static void requireExecutionShape(
            ActionStatus status, ActionExecutionMode executionMode) {
        if (status == ActionStatus.SUCCESS && executionMode == ActionExecutionMode.NOT_EXECUTED) {
            throw new IllegalArgumentException("a successful action must be REAL or DRY_RUN");
        }
        if (executionMode == ActionExecutionMode.DRY_RUN && status != ActionStatus.SUCCESS) {
            throw new IllegalArgumentException("only a successful action may be DRY_RUN");
        }
        if (status == ActionStatus.PRECONDITION_FAILED
                && executionMode != ActionExecutionMode.NOT_EXECUTED) {
            throw new IllegalArgumentException("a precondition failure must be NOT_EXECUTED");
        }
        if (status == ActionStatus.VERIFICATION_FAILED
                && executionMode != ActionExecutionMode.REAL) {
            throw new IllegalArgumentException(
                    "VERIFICATION_FAILED must report REAL execution mode");
        }
        if (status == ActionStatus.CANCELLED
                && executionMode != ActionExecutionMode.REAL
                && executionMode != ActionExecutionMode.NOT_EXECUTED) {
            throw new IllegalArgumentException("CANCELLED must report REAL or NOT_EXECUTED mode");
        }
    }
}

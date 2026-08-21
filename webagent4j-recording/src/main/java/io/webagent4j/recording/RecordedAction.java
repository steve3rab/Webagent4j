package io.webagent4j.recording;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import java.util.Objects;

/**
 * Safe, recorded projection of one action-backed step's outcome.
 *
 * <p>Mirrors {@code WorkflowActionSummary} field-for-field: only categorical, non-secret data is
 * retained, never a raw action result value. {@code actionId} is trace metadata only - a fresh
 * random correlation ID assigned per execution - so {@link WorkflowReplayVerifier} never compares
 * it: two otherwise-identical executions of the same workflow would always mismatch on this field
 * alone if it were compared, making it useless as a semantic signal.
 *
 * @param actionId the action pipeline's own correlation identifier, for diagnostics only
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
    }
}

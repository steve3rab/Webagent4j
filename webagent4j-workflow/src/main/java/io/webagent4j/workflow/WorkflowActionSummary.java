package io.webagent4j.workflow;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import java.util.Objects;

/**
 * Safe projection of an {@code ActionResult} for one action-backed workflow step.
 *
 * <p>Only categorical, non-secret fields are retained - never the action's raw {@code
 * ActionResult.value()}, its observations, or its underlying cause (see {@code
 * docs/workflow.md#action-integration}).
 *
 * @param actionId the action pipeline's own correlation identifier
 * @param actionType the executed operation category
 * @param status the action pipeline's terminal status
 * @param executionMode whether the backend was actually invoked, simulated, or never reached
 */
public record WorkflowActionSummary(
        ActionId actionId,
        ActionType actionType,
        ActionStatus status,
        ActionExecutionMode executionMode) {

    /** Validates summary data. */
    public WorkflowActionSummary {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(executionMode, "executionMode");
    }
}

package io.webagent4j.workflow;

import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionType;
import java.util.Objects;

/**
 * Restricted projection of an {@code ActionResult} for one action-backed workflow step.
 *
 * <p>Categorical action outcome fields and the action correlation identifier are retained. The
 * action's raw {@code ActionResult.value()}, observations, diagnostics, and underlying cause are
 * never retained (see {@code docs/workflow.md#action-integration}). {@code actionId} is metadata
 * supplied by the action pipeline and is not secret-redacted here.
 *
 * @param actionId the non-sensitive correlation metadata supplied by the action pipeline
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
        if ((status == ActionStatus.VERIFICATION_FAILED || status == ActionStatus.CANCELLED)
                && executionMode != ActionExecutionMode.REAL) {
            throw new IllegalArgumentException(status + " must report REAL execution mode");
        }
    }
}

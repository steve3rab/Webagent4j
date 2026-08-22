package io.webagent4j.recording;

import io.webagent4j.action.ActionDiagnostics;
import io.webagent4j.action.ActionEvent;
import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailure;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionStatus;
import io.webagent4j.action.ActionTimings;
import io.webagent4j.action.ActionType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Test-only helpers for building minimal, valid {@link ActionResult} fixtures. */
final class ActionResults {

    private ActionResults() {}

    static <R> ActionResult<R> success(R value) {
        return new ActionResult<>(
                true,
                value,
                Duration.ZERO,
                List.<ActionEvent>of(),
                Optional.empty(),
                ActionExecutionMode.REAL);
    }

    static <R> ActionResult<R> successWithActionId(ActionId actionId, R value) {
        return new ActionResult<>(
                actionId,
                ActionType.CLICK,
                ActionExecutionMode.REAL,
                ActionStatus.SUCCESS,
                value,
                Duration.ZERO,
                ActionTimings.empty(Duration.ZERO),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(),
                Optional.empty(),
                ActionDiagnostics.empty());
    }

    static <R> ActionResult<R> failure(ActionFailureType type, String message) {
        ActionStatus status =
                switch (type) {
                    case PRECONDITION_FAILED -> ActionStatus.PRECONDITION_FAILED;
                    case TIMEOUT -> ActionStatus.TIMEOUT;
                    case POSTCONDITION_FAILED -> ActionStatus.VERIFICATION_FAILED;
                    case INTERRUPTED -> ActionStatus.CANCELLED;
                    default -> ActionStatus.EXECUTION_FAILED;
                };
        ActionExecutionMode executionMode =
                type == ActionFailureType.PRECONDITION_FAILED
                                || type == ActionFailureType.TARGET_NOT_FOUND
                                || type == ActionFailureType.TARGET_AMBIGUOUS
                        ? ActionExecutionMode.NOT_EXECUTED
                        : ActionExecutionMode.REAL;
        return new ActionResult<>(
                ActionId.create(),
                ActionType.CLICK,
                executionMode,
                status,
                null,
                Duration.ZERO,
                ActionTimings.empty(Duration.ZERO),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.<ActionEvent>of(),
                Optional.of(new ActionFailure(type, message, Optional.empty())),
                ActionDiagnostics.empty());
    }

    static <R> ActionResult<R> interrupted(ActionExecutionMode executionMode) {
        return new ActionResult<>(
                ActionId.create(),
                ActionType.CLICK,
                executionMode,
                ActionStatus.CANCELLED,
                null,
                Duration.ZERO,
                ActionTimings.empty(Duration.ZERO),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(),
                Optional.of(
                        new ActionFailure(
                                ActionFailureType.INTERRUPTED,
                                "action interrupted",
                                Optional.empty())),
                ActionDiagnostics.empty());
    }
}

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
        return new ActionResult<>(
                false,
                null,
                Duration.ZERO,
                List.<ActionEvent>of(),
                Optional.of(new ActionFailure(type, message, Optional.empty())),
                ActionExecutionMode.REAL);
    }
}

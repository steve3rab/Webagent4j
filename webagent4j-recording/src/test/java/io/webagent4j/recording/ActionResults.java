package io.webagent4j.recording;

import io.webagent4j.action.ActionEvent;
import io.webagent4j.action.ActionExecutionMode;
import io.webagent4j.action.ActionFailure;
import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
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

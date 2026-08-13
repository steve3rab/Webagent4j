package io.webagent4j.action;

import java.util.Objects;
import java.util.Optional;

/**
 * Structured action failure suitable for diagnostics and future audit serialization.
 *
 * @param type stable failure category
 * @param message safe human-readable diagnostic
 * @param cause exceptional cause when one exists
 */
public record ActionFailure(ActionFailureType type, String message, Optional<Throwable> cause) {

    /** Validates failure data. */
    public ActionFailure {
        Objects.requireNonNull(type, "type");
        message = Objects.requireNonNull(message, "message");
        cause = Objects.requireNonNull(cause, "cause");
    }

    /** Renders only the safe category and sanitized message, never an exception payload. */
    @Override
    public String toString() {
        return "ActionFailure[type=" + type + ", message=" + message + "]";
    }
}

package io.webagent4j.action;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured outcome of an action and its postconditions.
 *
 * @param success whether execution and every postcondition succeeded
 * @param value action-specific value, which may be null only for {@link Void} actions
 * @param duration total duration
 * @param events immutable audit events
 * @param failure expected failure details when unsuccessful
 * @param <T> action value type
 */
public record ActionResult<T>(
        boolean success,
        T value,
        Duration duration,
        List<ActionEvent> events,
        Optional<ActionFailure> failure) {

    /** Validates and defensively stores action result data. */
    public ActionResult {
        Objects.requireNonNull(duration, "duration");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        failure = Objects.requireNonNull(failure, "failure");
        if (success && failure.isPresent()) {
            throw new IllegalArgumentException("successful actions cannot contain a failure");
        }
        if (!success && failure.isEmpty()) {
            throw new IllegalArgumentException("failed actions must contain a failure");
        }
    }
}

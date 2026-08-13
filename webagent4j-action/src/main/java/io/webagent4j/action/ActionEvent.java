package io.webagent4j.action;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable audit event emitted while executing an action.
 *
 * @param timestamp event time
 * @param action stable action name
 * @param target safe target description
 * @param result event outcome
 * @param duration elapsed duration at event time
 * @param metadata additional non-secret diagnostics
 */
public record ActionEvent(
        Instant timestamp,
        String action,
        String target,
        String result,
        Duration duration,
        Map<String, String> metadata) {

    /** Validates and defensively stores event data. */
    public ActionEvent {
        Objects.requireNonNull(timestamp, "timestamp");
        action = Objects.requireNonNull(action, "action");
        target = Objects.requireNonNull(target, "target");
        result = Objects.requireNonNull(result, "result");
        Objects.requireNonNull(duration, "duration");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}

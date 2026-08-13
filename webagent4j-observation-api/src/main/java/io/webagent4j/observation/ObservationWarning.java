package io.webagent4j.observation;

import java.util.Objects;
import java.util.Optional;

/** Immutable non-fatal observation warning with no sensitive source value. */
public record ObservationWarning(
        ObservationWarningType type, String message, Optional<SemanticElementId> elementId) {

    /** Validates warning data. */
    public ObservationWarning {
        Objects.requireNonNull(type, "type");
        message = Objects.requireNonNull(message, "message").trim();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("message cannot be blank");
        }
        Objects.requireNonNull(elementId, "elementId");
    }
}

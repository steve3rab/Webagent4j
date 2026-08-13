package io.webagent4j.observation;

import java.util.Objects;
import java.util.Optional;

/** Explicit immutable record of one applied observation limit. */
public record ObservationTruncation(
        ObservationTruncationType type,
        int originalCount,
        int retainedCount,
        Optional<SemanticElementId> elementId) {

    /** Validates truncation counts. */
    public ObservationTruncation {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(elementId, "elementId");
        if (originalCount < 0 || retainedCount < 0 || retainedCount > originalCount) {
            throw new IllegalArgumentException("invalid truncation counts");
        }
    }
}

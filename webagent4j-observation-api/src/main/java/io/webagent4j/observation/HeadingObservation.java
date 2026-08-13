package io.webagent4j.observation;

import java.util.Objects;
import java.util.Optional;

/** Immutable heading in document order with a simple logical parent. */
public record HeadingObservation(
        SemanticElementId elementId,
        int index,
        String text,
        int level,
        Optional<SemanticElementId> parentHeadingId) {

    /** Validates heading data. */
    public HeadingObservation {
        Objects.requireNonNull(elementId, "elementId");
        if (index <= 0 || level < 1 || level > 6) {
            throw new IllegalArgumentException("invalid heading index or level");
        }
        text = Objects.requireNonNull(text, "text");
        Objects.requireNonNull(parentHeadingId, "parentHeadingId");
    }
}

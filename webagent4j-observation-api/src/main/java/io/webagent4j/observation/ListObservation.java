package io.webagent4j.observation;

import java.util.List;
import java.util.Objects;

/** Immutable bounded semantic list summary. */
public record ListObservation(
        SemanticElementId elementId,
        boolean ordered,
        int itemCount,
        List<String> items,
        boolean truncated) {

    /** Validates and copies list data. */
    public ListObservation {
        Objects.requireNonNull(elementId, "elementId");
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount cannot be negative");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}

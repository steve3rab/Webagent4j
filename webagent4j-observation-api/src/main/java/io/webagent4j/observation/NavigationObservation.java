package io.webagent4j.observation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable navigation region, its links, current item, and declared orientation. */
public record NavigationObservation(
        SemanticElementId elementId,
        String name,
        List<SemanticElement> links,
        Optional<SemanticElementId> currentItem,
        NavigationOrientation orientation) {

    /** Validates and defensively stores navigation data. */
    public NavigationObservation {
        Objects.requireNonNull(elementId, "elementId");
        name = Objects.requireNonNull(name, "name");
        links = List.copyOf(Objects.requireNonNull(links, "links"));
        Objects.requireNonNull(currentItem, "currentItem");
        Objects.requireNonNull(orientation, "orientation");
    }
}

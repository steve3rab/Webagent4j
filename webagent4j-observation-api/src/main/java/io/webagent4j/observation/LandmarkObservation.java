package io.webagent4j.observation;

import io.webagent4j.locator.api.ElementRole;
import java.util.List;
import java.util.Objects;

/** Immutable accessibility landmark and its direct semantic children. */
public record LandmarkObservation(
        SemanticElementId elementId,
        ElementRole role,
        String name,
        List<SemanticElementId> children) {

    /** Validates and defensively stores landmark data. */
    public LandmarkObservation {
        Objects.requireNonNull(elementId, "elementId");
        Objects.requireNonNull(role, "role");
        name = Objects.requireNonNull(name, "name");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
    }
}

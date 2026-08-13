package io.webagent4j.observation;

import io.webagent4j.locator.api.ElementRole;
import java.util.List;
import java.util.Objects;

/** Immutable explicit ARIA menu or menubar summary. */
public record MenuObservation(
        SemanticElementId elementId, ElementRole role, String name, List<SemanticElementId> items) {

    /** Validates menu data. */
    public MenuObservation {
        Objects.requireNonNull(elementId, "elementId");
        if (role != ElementRole.MENU && role != ElementRole.MENUBAR) {
            throw new IllegalArgumentException("menu role must be MENU or MENUBAR");
        }
        name = Objects.requireNonNull(name, "name");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}

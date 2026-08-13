package io.webagent4j.observation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable tab-list summary with selected tab and associated panels when available. */
public record TabListObservation(
        SemanticElementId elementId,
        String name,
        List<SemanticElementId> tabs,
        Optional<SemanticElementId> selectedTab,
        List<SemanticRelationship> panelRelationships) {

    /** Validates and copies tab-list data. */
    public TabListObservation {
        Objects.requireNonNull(elementId, "elementId");
        name = Objects.requireNonNull(name, "name");
        tabs = List.copyOf(Objects.requireNonNull(tabs, "tabs"));
        Objects.requireNonNull(selectedTab, "selectedTab");
        panelRelationships =
                List.copyOf(Objects.requireNonNull(panelRelationships, "panelRelationships"));
    }
}

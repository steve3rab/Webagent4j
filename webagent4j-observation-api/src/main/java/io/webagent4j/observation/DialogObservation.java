package io.webagent4j.observation;

import java.util.List;
import java.util.Objects;

/** Immutable dialog or alert-dialog summary. */
public record DialogObservation(
        SemanticElementId elementId,
        String name,
        boolean modal,
        boolean visible,
        List<SemanticElementId> interactiveElements) {

    /** Validates and copies dialog data. */
    public DialogObservation {
        Objects.requireNonNull(elementId, "elementId");
        name = Objects.requireNonNull(name, "name");
        interactiveElements =
                List.copyOf(Objects.requireNonNull(interactiveElements, "interactiveElements"));
    }
}

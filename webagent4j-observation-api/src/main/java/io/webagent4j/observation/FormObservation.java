package io.webagent4j.observation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable semantic form with owned fields and submit/action controls. */
public record FormObservation(
        SemanticElementId elementId,
        String name,
        Optional<String> action,
        String method,
        List<FormFieldObservation> fields,
        List<SemanticElement> actions,
        boolean valid) {

    /** Validates and defensively stores form data. */
    public FormObservation {
        Objects.requireNonNull(elementId, "elementId");
        name = Objects.requireNonNull(name, "name");
        action =
                Objects.requireNonNull(action, "action")
                        .map(String::trim)
                        .filter(item -> !item.isEmpty());
        method = Objects.requireNonNull(method, "method").toUpperCase();
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
    }
}

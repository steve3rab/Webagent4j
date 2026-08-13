package io.webagent4j.observation;

import io.webagent4j.locator.api.ElementRole;
import java.util.Objects;

/** Immutable alert or status message. */
public record AlertObservation(
        SemanticElementId elementId, ElementRole role, String text, boolean visible) {

    /** Validates alert data. */
    public AlertObservation {
        Objects.requireNonNull(elementId, "elementId");
        if (role != ElementRole.ALERT && role != ElementRole.STATUS) {
            throw new IllegalArgumentException("alert role must be ALERT or STATUS");
        }
        text = Objects.requireNonNull(text, "text");
    }
}

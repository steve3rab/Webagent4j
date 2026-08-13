package io.webagent4j.locator;

import java.util.List;
import java.util.Objects;

/**
 * Immutable interactability decision with explainable failure reasons.
 *
 * @param interactable whether the element can reliably receive an action
 * @param reasons ordered reasons preventing interaction
 */
public record InteractabilityResult(
        boolean interactable, List<InteractabilityFailureReason> reasons) {

    /** Validates decision consistency and copies reasons. */
    public InteractabilityResult {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (interactable && !reasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "an interactable result cannot have failure reasons");
        }
        if (!interactable && reasons.isEmpty()) {
            throw new IllegalArgumentException("a failed result requires a reason");
        }
    }

    /** Creates a successful decision. */
    public static InteractabilityResult interactive() {
        return new InteractabilityResult(true, List.of());
    }

    /** Creates a failed decision with one or more reasons. */
    public static InteractabilityResult failed(List<InteractabilityFailureReason> reasons) {
        return new InteractabilityResult(false, reasons);
    }
}

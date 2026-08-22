package io.webagent4j.locator;

import io.webagent4j.dom.IElement;
import java.util.List;
import java.util.Objects;

/**
 * Immutable deduplicated candidate with aggregated evidence and deterministic ranking metadata.
 * Candidate identity is stable only within one document lifetime and is never derived from text, a
 * generated CSS selector or an arbitrary Java hash code.
 *
 * @param identity opaque backend identity used during one resolution
 * @param element live backend-neutral element reference
 * @param strategy highest-priority strategy contributing evidence
 * @param score internal deterministic ranking score from zero to one
 * @param confidence final selection confidence from zero to one
 * @param domOrder zero-based document order used only as the final {@code first()} tie-breaker
 * @param evidence immutable aggregated evidence
 * @param exactMatch whether at least one decisive deterministic match exists
 * @param hardConstraintsSatisfied whether all mandatory constraints passed
 * @param interactable whether reliable backend state marks the element interactable
 */
public record LocatorCandidate(
        String identity,
        IElement element,
        LocatorStrategyType strategy,
        double score,
        double confidence,
        int domOrder,
        List<LocatorEvidence> evidence,
        boolean exactMatch,
        boolean hardConstraintsSatisfied,
        boolean interactable) {

    /** Validates and defensively stores candidate data. */
    public LocatorCandidate {
        identity = Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(strategy, "strategy");
        validateUnit(score, "score");
        validateUnit(confidence, "confidence");
        if (domOrder < 0) {
            throw new IllegalArgumentException("domOrder cannot be negative");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }

    /** Returns whether this candidate contains evidence from the supplied strategy. */
    public boolean hasEvidence(LocatorStrategyType type) {
        return evidence.stream().anyMatch(item -> item.strategy() == type);
    }

    private static void validateUnit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }
}

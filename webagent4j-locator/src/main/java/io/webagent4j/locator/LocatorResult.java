package io.webagent4j.locator;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.LocatorDefinition;
import java.util.List;
import java.util.Objects;

/**
 * Immutable successful locator result retaining score, confidence, evidence and structured
 * diagnostics for future safety policies and self-healing analysis.
 *
 * @param definition requested immutable locator definition
 * @param element selected live re-resolvable element
 * @param strategy primary strategy that produced the element
 * @param score deterministic internal ranking score
 * @param confidence final confidence in the selection
 * @param exactMatch whether selection is backed by decisive exact evidence
 * @param candidates compatible deduplicated candidates in deterministic order
 * @param diagnostics machine-readable resolution diagnostics
 */
public record LocatorResult(
        LocatorDefinition definition,
        IElement element,
        LocatorStrategyType strategy,
        double score,
        double confidence,
        boolean exactMatch,
        List<LocatorCandidate> candidates,
        LocatorDiagnostics diagnostics) {

    /** Validates and defensively stores result data. */
    public LocatorResult {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(strategy, "strategy");
        validateUnit(score, "score");
        validateUnit(confidence, "confidence");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Renders a stable human-readable explanation through the dedicated renderer. */
    public String explain() {
        return new LocatorDiagnosticsRenderer().render(diagnostics, candidates);
    }

    /** Returns the formal successful resolution outcome. */
    public LocatorResolutionStatus status() {
        return LocatorResolutionStatus.RESOLVED;
    }

    private static void validateUnit(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }
}

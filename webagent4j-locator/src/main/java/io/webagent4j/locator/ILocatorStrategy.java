package io.webagent4j.locator;

import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.List;

/** Extensible candidate-discovery strategy used by the locator engine. */
public interface ILocatorStrategy {

    /** Returns the standard strategy type or {@link LocatorStrategyType#CUSTOM}. */
    LocatorStrategyType type();

    /** Returns a stable identifier used for deterministic custom-strategy ordering. */
    default String id() {
        return getClass().getName();
    }

    /** Returns the exact-first phase in which this strategy participates. */
    default LocatorStrategyPhase phase() {
        return type() == LocatorStrategyType.FUZZY_TEXT
                ? LocatorStrategyPhase.FALLBACK
                : LocatorStrategyPhase.DETERMINISTIC;
    }

    /**
     * Returns explicit priority within a phase. Higher values run first; equal priorities use the
     * stable strategy identifier. Standard plan order remains authoritative.
     */
    default int priority() {
        return 0;
    }

    /** Returns whether a custom strategy supports this locator definition. */
    default boolean supports(LocatorDefinition definition) {
        return type() != LocatorStrategyType.CUSTOM;
    }

    /**
     * Discovers bounded candidates. Custom strategies receive the complete definition, context and
     * candidates already collected, allowing future AI- or vision-backed plugins without changing
     * the deterministic engine.
     */
    LocatorBackendSearchResult discover(
            LocatorDefinition definition,
            LocatorPlanStep step,
            LocatorContext context,
            Duration timeout,
            int candidateLimit,
            List<LocatorCandidate> existingCandidates);
}

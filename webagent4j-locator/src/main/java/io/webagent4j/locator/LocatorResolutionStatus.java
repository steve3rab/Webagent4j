package io.webagent4j.locator;

/**
 * Stable semantic outcome of a locator resolution attempt.
 *
 * <p>The status formalizes the existing success and failure contracts without replacing their
 * result and exception types. A successful {@link LocatorResult} is {@link #RESOLVED}; locator
 * exceptions expose one of the remaining safe failure outcomes.
 */
public enum LocatorResolutionStatus {
    /** A unique candidate satisfied the semantic and state contract. */
    RESOLVED,

    /** Multiple candidates remain equivalent within the configured ambiguity margin. */
    AMBIGUOUS,

    /** Available machine-readable evidence cannot identify a sufficiently confident target. */
    UNRESOLVABLE,

    /**
     * Matching evidence exists, but every matching candidate violates an interaction constraint.
     */
    NOT_INTERACTABLE,

    /** An explicit wait ended before a matching candidate satisfied the requested conditions. */
    TIMEOUT
}

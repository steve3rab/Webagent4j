package io.webagent4j.locator;

import java.util.Objects;

/**
 * Immutable explainable evidence contributed by one strategy or state preference.
 *
 * @param strategy originating strategy
 * @param matchType evidence classification
 * @param expected safe expected summary
 * @param actual safe observed summary
 * @param contribution centralized score contribution
 */
public record LocatorEvidence(
        LocatorStrategyType strategy,
        LocatorMatchType matchType,
        String expected,
        String actual,
        double contribution) {

    /** Validates evidence values. */
    public LocatorEvidence {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(matchType, "matchType");
        expected = Objects.requireNonNull(expected, "expected");
        actual = Objects.requireNonNull(actual, "actual");
        if (!Double.isFinite(contribution) || contribution < 0.0 || contribution > 1.0) {
            throw new IllegalArgumentException("contribution must be between zero and one");
        }
    }
}

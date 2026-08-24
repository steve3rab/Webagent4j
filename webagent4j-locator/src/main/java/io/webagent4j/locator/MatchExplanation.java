package io.webagent4j.locator;

import java.util.Objects;

/**
 * One deterministic reason contributing to a candidate score.
 *
 * @param criterion criterion that was evaluated
 * @param requested requested value
 * @param actual observed candidate value
 * @param matched whether the criterion matched
 * @param contribution score contribution from zero to one
 */
public record MatchExplanation(
        String criterion, String requested, String actual, boolean matched, double contribution) {

    /** Validates explanation data. */
    public MatchExplanation {
        criterion = Objects.requireNonNull(criterion, "criterion");
        requested = Objects.requireNonNull(requested, "requested");
        actual = Objects.requireNonNull(actual, "actual");
        if (!Double.isFinite(contribution) || contribution < 0.0 || contribution > 1.0) {
            throw new IllegalArgumentException("contribution must be between zero and one");
        }
    }
}

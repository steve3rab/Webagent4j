package io.webagent4j.locator;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable global work budget for one locator resolution.
 *
 * @param timeout maximum elapsed resolution time
 * @param maxCandidates maximum candidates retained and scored
 * @param maxStrategies maximum strategies executed
 * @param maxFuzzyCandidates maximum candidates inspected by fuzzy matching
 */
public record LocatorResolutionBudget(
        Duration timeout, int maxCandidates, int maxStrategies, int maxFuzzyCandidates) {

    /** Validates positive budget limits. */
    public LocatorResolutionBudget {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxCandidates < 1 || maxStrategies < 1 || maxFuzzyCandidates < 1) {
            throw new IllegalArgumentException("resolution budget limits must be positive");
        }
    }

    /** Returns pragmatic default limits for interactive browser resolution. */
    public static LocatorResolutionBudget defaults() {
        return new LocatorResolutionBudget(Duration.ofSeconds(5), 100, 10, 50);
    }

    /** Returns the default limits with a caller-supplied timeout. */
    public static LocatorResolutionBudget defaults(Duration timeout) {
        return new LocatorResolutionBudget(timeout, 100, 10, 50);
    }
}

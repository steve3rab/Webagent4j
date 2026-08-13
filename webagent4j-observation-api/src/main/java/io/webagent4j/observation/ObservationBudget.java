package io.webagent4j.observation;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable global limits that prevent unbounded traversal, memory use, and serialized output.
 *
 * @param timeout total capture and transformation deadline
 * @param maxElements maximum semantic elements retained
 * @param maxDepth maximum semantic-tree depth
 * @param maxTextLength maximum retained text length per value
 * @param maxTableRows maximum rows retained per table
 * @param maxTableColumns maximum columns retained per table
 * @param maxListItems maximum items retained per list
 * @param maxSelectOptions maximum options retained per select
 */
public record ObservationBudget(
        Duration timeout,
        int maxElements,
        int maxDepth,
        int maxTextLength,
        int maxTableRows,
        int maxTableColumns,
        int maxListItems,
        int maxSelectOptions) {

    /** Validates all limits. */
    public ObservationBudget {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        requirePositive(maxElements, "maxElements");
        requirePositive(maxDepth, "maxDepth");
        requirePositive(maxTextLength, "maxTextLength");
        requirePositive(maxTableRows, "maxTableRows");
        requirePositive(maxTableColumns, "maxTableColumns");
        requirePositive(maxListItems, "maxListItems");
        requirePositive(maxSelectOptions, "maxSelectOptions");
    }

    /** Returns safe standard limits requiring no user configuration. */
    public static ObservationBudget defaults() {
        return new ObservationBudget(Duration.ofSeconds(5), 500, 10, 4_000, 100, 50, 100, 100);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

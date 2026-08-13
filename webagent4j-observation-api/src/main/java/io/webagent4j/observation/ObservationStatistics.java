package io.webagent4j.observation;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable capture statistics and explicit truncation data.
 *
 * @param elementsVisited backend semantic candidates visited
 * @param elementsIncluded elements retained in the immutable model
 * @param elementsFiltered semantic candidates rejected by deterministic filters
 * @param interactiveElements retained actionable elements
 * @param forms retained forms
 * @param links retained links
 * @param buttons retained buttons
 * @param tables retained tables
 * @param duration total observation duration
 * @param truncations explicit applied limits
 */
public record ObservationStatistics(
        int elementsVisited,
        int elementsIncluded,
        int elementsFiltered,
        int interactiveElements,
        int forms,
        int links,
        int buttons,
        int tables,
        Duration duration,
        List<ObservationTruncation> truncations) {

    /** Validates counts and defensively stores data. */
    public ObservationStatistics {
        if (elementsVisited < 0
                || elementsIncluded < 0
                || elementsFiltered < 0
                || interactiveElements < 0
                || forms < 0
                || links < 0
                || buttons < 0
                || tables < 0) {
            throw new IllegalArgumentException("observation counts cannot be negative");
        }
        Objects.requireNonNull(duration, "duration");
        truncations = List.copyOf(Objects.requireNonNull(truncations, "truncations"));
    }

    /** Returns whether any observation limit was applied. */
    public boolean truncated() {
        return !truncations.isEmpty();
    }
}

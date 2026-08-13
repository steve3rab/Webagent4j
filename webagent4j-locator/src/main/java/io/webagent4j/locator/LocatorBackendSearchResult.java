package io.webagent4j.locator;

import java.util.List;
import java.util.Objects;

/**
 * Bounded backend discovery result.
 *
 * @param candidates candidates returned within the engine budget
 * @param discoveredCount total candidates observed before truncation
 * @param truncated whether a candidate budget truncated the result
 */
public record LocatorBackendSearchResult(
        List<LocatorBackendCandidate> candidates, int discoveredCount, boolean truncated) {

    /** Validates counts and copies candidates. */
    public LocatorBackendSearchResult {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (discoveredCount < candidates.size()) {
            throw new IllegalArgumentException("discoveredCount cannot be smaller than candidates");
        }
        if (truncated && discoveredCount <= candidates.size()) {
            throw new IllegalArgumentException("truncated results require omitted candidates");
        }
    }

    /** Creates an untruncated result. */
    public static LocatorBackendSearchResult complete(List<LocatorBackendCandidate> candidates) {
        return new LocatorBackendSearchResult(candidates, candidates.size(), false);
    }
}

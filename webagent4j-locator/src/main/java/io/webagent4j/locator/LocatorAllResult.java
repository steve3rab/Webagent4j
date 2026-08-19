package io.webagent4j.locator;

import java.util.List;
import java.util.Objects;

/**
 * Immutable {@link ILocatorEngine#locateAllWithScopePath} outcome: every compatible candidate,
 * together with the scope path actually used to find them.
 *
 * @param candidates compatible candidates in deterministic rank order
 * @param scopePath the hierarchical scope path live-resolved for this search - the same shape
 *     {@link LocatorDiagnostics#scopePath()} already reports for {@link ILocatorEngine#locate}/
 *     {@link ILocatorEngine#locateSingle}, not necessarily the caller's starting baseline scope
 *     when a structured scope (for example a frame chain) is re-resolved fresh on every polling
 *     attempt
 */
public record LocatorAllResult(List<LocatorCandidate> candidates, List<String> scopePath) {

    /** Defensively copies both lists. */
    public LocatorAllResult {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        scopePath = List.copyOf(Objects.requireNonNull(scopePath, "scopePath"));
    }
}

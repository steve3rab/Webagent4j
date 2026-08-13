package io.webagent4j.locator;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.TextMatch;
import java.util.Objects;
import java.util.Optional;

/**
 * Focused query sent to a concrete browser backend.
 *
 * @param strategy discovery strategy
 * @param role optional semantic role
 * @param text optional text criterion
 * @param attributeName optional attribute name
 * @param value optional exact selector or attribute value
 */
public record LocatorBackendQuery(
        LocatorStrategyType strategy,
        Optional<ElementRole> role,
        Optional<TextMatch> text,
        Optional<String> attributeName,
        Optional<String> value) {

    /** Validates query values. */
    public LocatorBackendQuery {
        Objects.requireNonNull(strategy, "strategy");
        role = Objects.requireNonNull(role, "role");
        text = Objects.requireNonNull(text, "text");
        attributeName = Objects.requireNonNull(attributeName, "attributeName");
        value = Objects.requireNonNull(value, "value");
    }
}

package io.webagent4j.observation;

import java.util.Objects;

/**
 * Immutable semantic identity used for deduplication and relationships within an observation.
 *
 * <p>The identity is not promised to survive navigation. Use locator evidence for later
 * re-resolution and semantic diff matching.
 */
public record SemanticElementId(String value) {

    /** Validates the identifier value. */
    public SemanticElementId {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value cannot be blank");
        }
    }
}

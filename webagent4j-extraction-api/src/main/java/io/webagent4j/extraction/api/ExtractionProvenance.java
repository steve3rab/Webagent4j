package io.webagent4j.extraction.api;

import io.webagent4j.locator.api.LocatorDefinition;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable record of where an {@link ExtractionResult} came from, so a caller can understand a
 * surprising value without re-running the extraction.
 *
 * @param scopePath the hierarchical scope the source was resolved in - page, any structured or
 *     frame boundaries crossed to reach it - in the same shape locator diagnostics already use
 * @param source the {@link LocatorDefinition} that resolved the source element
 * @param readType which raw datum was read
 * @param attributeName the attribute name read, present only for {@link
 *     ExtractionReadType#ATTRIBUTE}
 */
public record ExtractionProvenance(
        List<String> scopePath,
        LocatorDefinition source,
        ExtractionReadType readType,
        Optional<String> attributeName) {

    /** Defensively copies the scope path and validates required fields. */
    public ExtractionProvenance {
        scopePath = List.copyOf(Objects.requireNonNull(scopePath, "scopePath"));
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(readType, "readType");
        Objects.requireNonNull(attributeName, "attributeName");
    }
}

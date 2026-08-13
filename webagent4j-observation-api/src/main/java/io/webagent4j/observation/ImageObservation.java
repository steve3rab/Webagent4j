package io.webagent4j.observation;

import java.util.Objects;
import java.util.Optional;

/** Immutable metadata for a meaningful image; no image bytes are downloaded or retained. */
public record ImageObservation(
        SemanticElementId elementId,
        String accessibleName,
        Optional<String> alt,
        Optional<String> source,
        int width,
        int height) {

    /** Validates image metadata. */
    public ImageObservation {
        Objects.requireNonNull(elementId, "elementId");
        accessibleName = Objects.requireNonNull(accessibleName, "accessibleName");
        alt = normalized(alt, "alt");
        source = normalized(source, "source");
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("image dimensions cannot be negative");
        }
    }

    private static Optional<String> normalized(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name)
                .map(String::trim)
                .filter(item -> !item.isEmpty());
    }
}

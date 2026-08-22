package io.webagent4j.observation.spi;

import io.webagent4j.observation.ViewportSize;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable backend-neutral batch snapshot transformed by the observation engine.
 *
 * <p>This SPI DTO is not the public semantic page model. It is bounded, contains no full HTML or
 * binary resource, and must never contain a secret field value.
 */
@SuppressWarnings("checkstyle:ParameterNumber")
public record PageSnapshot(
        String url,
        String title,
        Optional<String> language,
        Optional<String> charset,
        String readyState,
        ViewportSize viewport,
        Optional<String> canonicalUrl,
        Optional<String> description,
        List<SnapshotElement> elements,
        int elementsVisited,
        int originalSemanticElementCount,
        Duration captureDuration,
        boolean mutationDetected,
        List<String> warnings) {

    /** Validates and defensively stores captured data. */
    public PageSnapshot {
        url = Objects.requireNonNull(url, "url");
        title = Objects.requireNonNull(title, "title");
        language = normalized(language, "language");
        charset = normalized(charset, "charset");
        readyState = Objects.requireNonNull(readyState, "readyState");
        Objects.requireNonNull(viewport, "viewport");
        canonicalUrl = normalized(canonicalUrl, "canonicalUrl");
        description = normalized(description, "description");
        elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        if (elementsVisited < 0 || originalSemanticElementCount < 0) {
            throw new IllegalArgumentException("snapshot counts cannot be negative");
        }
        Objects.requireNonNull(captureDuration, "captureDuration");
        if (captureDuration.isNegative()) {
            throw new IllegalArgumentException("captureDuration cannot be negative");
        }
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    private static Optional<String> normalized(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name)
                .map(String::trim)
                .filter(item -> !item.isEmpty());
    }
}

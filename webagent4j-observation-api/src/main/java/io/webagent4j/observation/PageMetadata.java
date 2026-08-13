package io.webagent4j.observation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable page-level metadata captured as part of one coherent semantic snapshot.
 *
 * @param url URL at capture time
 * @param title document title
 * @param language declared document language when present
 * @param charset declared character set when present
 * @param readyState document ready state
 * @param capturedAt injected-clock capture timestamp
 * @param viewport viewport dimensions
 * @param canonicalUrl canonical URL when declared
 * @param description document description when declared
 */
public record PageMetadata(
        String url,
        String title,
        Optional<String> language,
        Optional<String> charset,
        String readyState,
        Instant capturedAt,
        ViewportSize viewport,
        Optional<String> canonicalUrl,
        Optional<String> description) {

    /** Validates all immutable metadata fields. */
    public PageMetadata {
        url = Objects.requireNonNull(url, "url");
        title = Objects.requireNonNull(title, "title");
        language = normalized(language, "language");
        charset = normalized(charset, "charset");
        readyState = Objects.requireNonNull(readyState, "readyState");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(viewport, "viewport");
        canonicalUrl = normalized(canonicalUrl, "canonicalUrl");
        description = normalized(description, "description");
    }

    private static Optional<String> normalized(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name)
                .map(String::trim)
                .filter(item -> !item.isEmpty());
    }
}

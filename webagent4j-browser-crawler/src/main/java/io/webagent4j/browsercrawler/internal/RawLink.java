package io.webagent4j.browsercrawler.internal;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * One link discovered in a rendered DOM, before normalization/scope/dedup decisions - the browser
 * equivalent of {@code ExtractedLink} in {@code webagent4j-crawler} (jsoup-parsed static HTML).
 */
public record RawLink(
        URI resolvedUrl, String rawHref, Optional<String> anchorText, int documentOrder) {

    public RawLink {
        Objects.requireNonNull(resolvedUrl, "resolvedUrl");
        Objects.requireNonNull(rawHref, "rawHref");
        Objects.requireNonNull(anchorText, "anchorText");
        if (documentOrder < 0) {
            throw new IllegalArgumentException("documentOrder must be >= 0, was " + documentOrder);
        }
    }
}

package io.webagent4j.browsercrawler.internal;

import io.webagent4j.crawler.api.LinkKind;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * One link discovered in a rendered DOM, before normalization/scope/dedup decisions - the browser
 * equivalent of {@code ExtractedLink} in {@code webagent4j-crawler} (jsoup-parsed static HTML).
 *
 * @param kind which HTML element produced this link ({@code <a href>} or {@code <area href>}) -
 *     carried through unchanged to every {@code DiscoveredLink} this raw link becomes, on every
 *     decision path (accepted, out-of-scope, duplicate, max-depth, max-pages, cancelled), so
 *     provenance is never silently lost or defaulted along the way
 */
public record RawLink(
        URI resolvedUrl,
        String rawHref,
        Optional<String> anchorText,
        LinkKind kind,
        int documentOrder) {

    public RawLink {
        Objects.requireNonNull(resolvedUrl, "resolvedUrl");
        Objects.requireNonNull(rawHref, "rawHref");
        Objects.requireNonNull(anchorText, "anchorText");
        Objects.requireNonNull(kind, "kind");
        if (documentOrder < 0) {
            throw new IllegalArgumentException("documentOrder must be >= 0, was " + documentOrder);
        }
    }
}

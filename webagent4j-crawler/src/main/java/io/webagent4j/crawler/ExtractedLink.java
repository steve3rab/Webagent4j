package io.webagent4j.crawler;

import io.webagent4j.crawler.api.LinkKind;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * One navigation link exactly as extracted from HTML, before any crawl scope/policy decision - the
 * raw material {@link HttpCrawler} turns into a {@link io.webagent4j.crawler.api.DiscoveredLink}
 * once it knows whether the link is allowed.
 *
 * @param resolvedUrl the href resolved to an absolute URL against the document's effective base URI
 * @param rawHref the exact {@code href} attribute value as it appeared in the HTML
 * @param anchorText the link's visible text, when present and non-blank
 * @param kind which HTML element produced this link
 * @param documentOrder zero-based position among all links extracted from the same page
 */
public record ExtractedLink(
        URI resolvedUrl,
        String rawHref,
        Optional<String> anchorText,
        LinkKind kind,
        int documentOrder) {

    /** Validates required fields. */
    public ExtractedLink {
        Objects.requireNonNull(resolvedUrl, "resolvedUrl");
        Objects.requireNonNull(rawHref, "rawHref");
        Objects.requireNonNull(anchorText, "anchorText");
        Objects.requireNonNull(kind, "kind");
        if (documentOrder < 0) {
            throw new IllegalArgumentException("documentOrder cannot be negative");
        }
    }
}

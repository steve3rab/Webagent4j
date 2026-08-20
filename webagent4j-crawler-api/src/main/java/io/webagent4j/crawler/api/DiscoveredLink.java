package io.webagent4j.crawler.api;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * One navigation link found on a crawled page, before or after the crawl scope/policy decision -
 * used both to populate the frontier and to explain, in {@link CrawlResult#rejectedUrls()}, why a
 * particular URL was never fetched.
 *
 * @param resolvedUrl the href resolved to an absolute URL against the page's base URI, before
 *     normalization
 * @param normalizedUrl the deduplication identity ({@link IUrlNormalizer#normalize(URI)} applied to
 *     {@code resolvedUrl}), present only when normalization was actually attempted and succeeded -
 *     a link rejected before normalization (an unsupported scheme, an out-of-scope host) or one
 *     whose {@code resolvedUrl} {@link IUrlNormalizer} itself could not normalize carries {@link
 *     Optional#empty()} here rather than a value that was never actually computed
 * @param rawHref the exact {@code href} attribute value as it appeared in the HTML
 * @param anchorText the link's visible text, when present and non-blank
 * @param kind which HTML element produced this link
 * @param allowed whether this link was allowed into the frontier
 * @param rejection the rejection decision, present exactly when {@code allowed} is {@code false}
 * @param documentOrder zero-based position among all links extracted from the same page, in
 *     document order
 */
public record DiscoveredLink(
        URI resolvedUrl,
        Optional<URI> normalizedUrl,
        String rawHref,
        Optional<String> anchorText,
        LinkKind kind,
        boolean allowed,
        Optional<CrawlDecision> rejection,
        int documentOrder) {

    /** Validates required fields and the allowed/rejection/normalizedUrl consistency invariants. */
    public DiscoveredLink {
        Objects.requireNonNull(resolvedUrl, "resolvedUrl");
        Objects.requireNonNull(normalizedUrl, "normalizedUrl");
        Objects.requireNonNull(rawHref, "rawHref");
        Objects.requireNonNull(anchorText, "anchorText");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(rejection, "rejection");
        if (allowed && rejection.isPresent()) {
            throw new IllegalArgumentException("an allowed link cannot carry a rejection decision");
        }
        if (!allowed && rejection.isEmpty()) {
            throw new IllegalArgumentException("a rejected link must carry a rejection decision");
        }
        if (allowed && normalizedUrl.isEmpty()) {
            throw new IllegalArgumentException(
                    "an allowed link must carry the normalized URL it was allowed under");
        }
        if (documentOrder < 0) {
            throw new IllegalArgumentException("documentOrder cannot be negative");
        }
    }
}

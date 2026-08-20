package io.webagent4j.crawler;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything {@link IHtmlLinkExtractor} extracts from one HTML document.
 *
 * @param links every navigation link found, in document order
 * @param title the document's {@code <title>} text, when present and non-blank
 * @param declaredCanonicalUrl the page's declared {@code <link rel="canonical">} target, resolved
 *     to an absolute URL
 */
public record LinkExtractionResult(
        List<ExtractedLink> links, Optional<String> title, Optional<URI> declaredCanonicalUrl) {

    /** Validates required fields and defensively copies the link list. */
    public LinkExtractionResult {
        links = List.copyOf(Objects.requireNonNull(links, "links"));
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(declaredCanonicalUrl, "declaredCanonicalUrl");
    }
}

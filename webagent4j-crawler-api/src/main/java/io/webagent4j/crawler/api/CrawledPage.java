package io.webagent4j.crawler.api;

import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable representation of one HTML page successfully fetched during a crawl.
 *
 * <p>Every collection is defensively copied and returned unmodifiable; no mutable state escapes
 * this type.
 *
 * @param requestedUrl the URL the fetch attempt started from
 * @param finalUrl the URL actually reached, after following any redirects
 * @param depth this page's crawl depth (a seed is depth 0)
 * @param discoveredFrom the page this URL was discovered on; absent only for a seed
 * @param statusCode the final response's HTTP status code (always {@code 2xx})
 * @param headers the final response's headers, preserving multi-value order
 * @param contentType the final response's {@code Content-Type}, without parameters
 * @param charset the charset the body was decoded with, when one could be determined
 * @param html the decoded response body
 * @param title the document's {@code <title>} text, when present and non-blank
 * @param declaredCanonicalUrl the page's declared {@code <link rel="canonical">} target, resolved
 *     to an absolute URL; never automatically substituted for {@code finalUrl}
 * @param links every navigation link extracted from this page, in document order, including
 *     rejected ones
 * @param redirectChain every redirect hop followed to reach {@code finalUrl}
 * @param responseBytes the final response body's size in bytes
 * @param fetchDuration monotonic-clock-measured time spent on this page's entire fetch attempt,
 *     including every redirect and retry - never measured against a wall clock, which can jump
 *     backwards or forwards independently of elapsed time
 * @param provenance where this page came from
 */
public record CrawledPage(
        URI requestedUrl,
        URI finalUrl,
        int depth,
        Optional<URI> discoveredFrom,
        int statusCode,
        Map<String, List<String>> headers,
        String contentType,
        Optional<Charset> charset,
        String html,
        Optional<String> title,
        Optional<URI> declaredCanonicalUrl,
        List<DiscoveredLink> links,
        List<RedirectHop> redirectChain,
        long responseBytes,
        Duration fetchDuration,
        CrawlPageProvenance provenance) {

    /** Validates required fields and defensively copies every collection. */
    public CrawledPage {
        Objects.requireNonNull(requestedUrl, "requestedUrl");
        Objects.requireNonNull(finalUrl, "finalUrl");
        Objects.requireNonNull(discoveredFrom, "discoveredFrom");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(declaredCanonicalUrl, "declaredCanonicalUrl");
        Objects.requireNonNull(provenance, "provenance");
        headers = copyHeaders(headers);
        links = List.copyOf(Objects.requireNonNull(links, "links"));
        redirectChain = List.copyOf(Objects.requireNonNull(redirectChain, "redirectChain"));
        if (depth < 0) {
            throw new IllegalArgumentException("depth cannot be negative");
        }
        if (statusCode < 200 || statusCode > 299) {
            throw new IllegalArgumentException("statusCode must be 2xx, was " + statusCode);
        }
        if (responseBytes < 0) {
            throw new IllegalArgumentException("responseBytes cannot be negative");
        }
        Objects.requireNonNull(fetchDuration, "fetchDuration");
        if (fetchDuration.isNegative()) {
            throw new IllegalArgumentException("fetchDuration cannot be negative");
        }
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
        Objects.requireNonNull(headers, "headers");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Map.copyOf(copy);
    }
}

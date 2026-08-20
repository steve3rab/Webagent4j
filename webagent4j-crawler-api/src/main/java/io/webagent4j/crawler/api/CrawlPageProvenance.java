package io.webagent4j.crawler.api;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Where one {@link CrawledPage} came from: enough to reconstruct the discovery chain from a seed to
 * this page without retaining the whole crawl's discovery graph.
 *
 * @param seedOrigin the seed URL whose traversal eventually discovered this page
 * @param discoveredFrom the page this URL was discovered on; absent only for a seed itself
 * @param depth this page's crawl depth (a seed is depth 0)
 * @param requestedUrl the URL the fetch attempt started from
 * @param finalUrl the URL actually reached, after following {@code redirectChain}
 * @param redirectChain every redirect hop followed to reach {@code finalUrl}, in order
 */
public record CrawlPageProvenance(
        URI seedOrigin,
        Optional<URI> discoveredFrom,
        int depth,
        URI requestedUrl,
        URI finalUrl,
        List<RedirectHop> redirectChain) {

    /** Validates required fields and defensively copies the redirect chain. */
    public CrawlPageProvenance {
        Objects.requireNonNull(seedOrigin, "seedOrigin");
        Objects.requireNonNull(discoveredFrom, "discoveredFrom");
        Objects.requireNonNull(requestedUrl, "requestedUrl");
        Objects.requireNonNull(finalUrl, "finalUrl");
        redirectChain = List.copyOf(Objects.requireNonNull(redirectChain, "redirectChain"));
        if (depth < 0) {
            throw new IllegalArgumentException("depth cannot be negative");
        }
    }
}

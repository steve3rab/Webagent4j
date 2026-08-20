package io.webagent4j.crawler.internal;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * One pending unit of work in the frontier: a normalized URL to fetch, at a known depth, with
 * enough provenance to build a {@link io.webagent4j.crawler.api.CrawlPageProvenance} once fetched.
 * Never exposed outside the engine module.
 *
 * @param url the normalized URL to fetch
 * @param depth this URL's crawl depth
 * @param seedOrigin the seed whose traversal discovered this URL
 * @param discoveredFrom the page this URL was discovered on; absent only for a seed
 */
public record CrawlTask(URI url, int depth, URI seedOrigin, Optional<URI> discoveredFrom) {

    /** Validates required fields. */
    public CrawlTask {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(seedOrigin, "seedOrigin");
        Objects.requireNonNull(discoveredFrom, "discoveredFrom");
        if (depth < 0) {
            throw new IllegalArgumentException("depth cannot be negative");
        }
    }
}

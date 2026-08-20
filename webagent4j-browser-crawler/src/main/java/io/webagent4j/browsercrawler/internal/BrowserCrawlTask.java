package io.webagent4j.browsercrawler.internal;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * One claimed, sequence-numbered navigation, never exposed outside this engine.
 *
 * <p>{@code sequence} is assigned once, at discovery/enqueue time, by a single coordinator thread -
 * it is the deterministic frontier order that the engine's commit loop replays regardless of actual
 * navigation completion timing under concurrency.
 */
public record BrowserCrawlTask(
        long sequence, URI url, int depth, URI seedOrigin, Optional<URI> discoveredFrom) {

    public BrowserCrawlTask {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(seedOrigin, "seedOrigin");
        Objects.requireNonNull(discoveredFrom, "discoveredFrom");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0, was " + sequence);
        }
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0, was " + depth);
        }
    }
}

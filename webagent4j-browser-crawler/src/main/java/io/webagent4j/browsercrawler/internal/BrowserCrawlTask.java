package io.webagent4j.browsercrawler.internal;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * One claimed, sequence-numbered navigation, never exposed outside this engine.
 *
 * <p>{@code sequence} is assigned exactly once, at claim/enqueue time, by the single thread that
 * runs the whole crawl - it defines the deterministic FIFO frontier order the engine's commit loop
 * replays. There is no physical navigation concurrency in this engine to reorder against (see
 * {@code docs/browser-crawler.md#concurrency-model}); this field's determinism is structural, not a
 * guarantee that holds only in spite of concurrent completion timing.
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

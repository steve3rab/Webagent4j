package io.webagent4j.browsercrawler;

/**
 * Why a browser crawl stopped.
 *
 * <p>A separate type from {@code CrawlTerminationReason} ({@code webagent4j-crawler-api}) rather
 * than an addition to it: cancellation has no HTTP crawler equivalent (Phase 0.6 supports no
 * cancellation at all), and adding it to the existing, already-shipped enum would be a needless
 * change to a Phase 0.6 public contract for a Phase 0.7 concept. If precedence matters - for
 * example cancellation observed after {@code maxPages} already stopped the crawl - {@link
 * #CANCELLED} takes priority over {@link #MAX_PAGES_REACHED}, which takes priority over {@link
 * #FAIL_FAST}, which takes priority over {@link #COMPLETED}. See {@code
 * docs/browser-crawler.md#determinism-contract}.
 */
public enum BrowserCrawlTerminationReason {

    /** The frontier was exhausted; every discovered, in-scope URL was claimed and processed. */
    COMPLETED,

    /** {@code maxPages} was reached before the frontier was exhausted. */
    MAX_PAGES_REACHED,

    /** {@link CancellationToken#cancel()} was observed before the frontier was exhausted. */
    CANCELLED,

    /** {@code failFast} stopped the crawl after a fatal page failure. */
    FAIL_FAST
}

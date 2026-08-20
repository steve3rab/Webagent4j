package io.webagent4j.crawler.api;

/** Why a {@link CrawlResult}'s crawl loop stopped. */
public enum CrawlTerminationReason {

    /**
     * The frontier ran empty: every discovered URL was fetched, rejected, exceeded {@link
     * CrawlRequest#maxDepth()}, or was a duplicate. Reaching {@code maxDepth} on its own is not a
     * distinct termination reason - it is simply why the frontier emptied.
     */
    COMPLETED,

    /** {@link CrawlRequest#maxPages()} unique fetch attempts were started. */
    MAX_PAGES_REACHED,

    /**
     * An unrecoverable, opaque failure stopped the crawl entirely - only reachable when {@link
     * CrawlRequest#failFast()} is set, or when the failure is a genuine programming/backend error
     * rather than a normal per-page failure.
     */
    FATAL_ERROR
}

package io.webagent4j.crawler.api;

/**
 * Order in which the frontier releases discovered URLs for fetching.
 *
 * <p>Only {@link #BREADTH_FIRST} is implemented in this phase - {@link CrawlRequest} rejects {@link
 * #DEPTH_FIRST} at construction time rather than silently falling back to breadth-first traversal.
 */
public enum TraversalStrategy {

    /** Every URL at depth N is fetched before any URL at depth N+1, in discovery order. */
    BREADTH_FIRST,

    /** Reserved for a future phase; not yet implemented. */
    DEPTH_FIRST
}

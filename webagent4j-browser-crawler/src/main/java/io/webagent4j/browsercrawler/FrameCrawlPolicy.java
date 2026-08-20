package io.webagent4j.browsercrawler;

/**
 * How a {@link BrowserCrawler} discovers links inside {@code <iframe>} documents, in addition to
 * the top-level document of every crawled page.
 *
 * <p>Only {@link #TOP_LEVEL_ONLY} is implemented in this phase. {@link #SAME_ORIGIN_FRAMES} and
 * {@link #ALL_ACCESSIBLE_FRAMES} are declared for the shape a future phase would use, but {@link
 * BrowserCrawlRequest} rejects them at construction - the current backend-neutral browser API
 * ({@code IPage}/{@code IFrame}) has no operation to enumerate every frame on a page (only to
 * locate one frame matching an id/name/title/URL criterion), so a genuine "all frames" traversal
 * cannot be built without first extending that public API, which is out of scope for this phase.
 * This mirrors {@code TraversalStrategy.DEPTH_FIRST} in {@code webagent4j-crawler-api}: declared,
 * documented, and explicitly rejected rather than silently ignored or half-implemented.
 */
public enum FrameCrawlPolicy {

    /** Discover links only from each crawled page's own top-level document. The default. */
    TOP_LEVEL_ONLY,

    /** Reserved for a future phase. Rejected at {@link BrowserCrawlRequest} construction. */
    SAME_ORIGIN_FRAMES,

    /** Reserved for a future phase. Rejected at {@link BrowserCrawlRequest} construction. */
    ALL_ACCESSIBLE_FRAMES
}

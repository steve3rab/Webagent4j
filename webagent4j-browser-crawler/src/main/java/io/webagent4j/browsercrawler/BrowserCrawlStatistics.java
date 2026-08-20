package io.webagent4j.browsercrawler;

/**
 * Independent counters describing one completed {@link BrowserCrawlResult}.
 *
 * <p>As with {@code CrawlStatistics} ({@code webagent4j-crawler-api}), no field is defined in terms
 * of another - each has its own precise definition below. Concurrency never makes these counts
 * race-dependent: every count is produced by the single coordinator thread committing task outcomes
 * in deterministic frontier order (see {@code docs/browser-crawler.md#bounded-concurrency}), never
 * incremented directly by a worker thread.
 *
 * @param discoveredUrls every URL discovered from a page's rendered DOM, before scope/dedup/limit
 *     decisions - including duplicates and out-of-scope links
 * @param claimedNavigations every normalized URL that passed scope, dedup, and the {@code maxPages}
 *     gate - the authoritative count {@code maxPages} bounds. Includes claimed tasks the crawl
 *     stopped before actually dispatching (cancellation or {@code failFast}); see {@link
 *     #successfulPages()}/{@link #failedPages()} for what was actually navigated
 * @param successfulPages navigations that produced a {@link BrowserCrawledPage}
 * @param failedPages navigations that produced a {@link BrowserCrawlFailure}
 * @param duplicateUrls discovered URLs whose normalized identity was already claimed by an earlier
 *     task
 * @param outOfScopeUrls discovered URLs rejected by the scope policy before navigation
 * @param cancelledTasks claimed navigations that did not start because cancellation was already
 *     observed
 * @param maxDepthReached the greatest depth actually assigned to any claimed navigation
 */
public record BrowserCrawlStatistics(
        int discoveredUrls,
        int claimedNavigations,
        int successfulPages,
        int failedPages,
        int duplicateUrls,
        int outOfScopeUrls,
        int cancelledTasks,
        int maxDepthReached) {

    /** Validates that every counter is non-negative. */
    public BrowserCrawlStatistics {
        requireNonNegative(discoveredUrls, "discoveredUrls");
        requireNonNegative(claimedNavigations, "claimedNavigations");
        requireNonNegative(successfulPages, "successfulPages");
        requireNonNegative(failedPages, "failedPages");
        requireNonNegative(duplicateUrls, "duplicateUrls");
        requireNonNegative(outOfScopeUrls, "outOfScopeUrls");
        requireNonNegative(cancelledTasks, "cancelledTasks");
        requireNonNegative(maxDepthReached, "maxDepthReached");
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be >= 0, was " + value);
        }
    }
}

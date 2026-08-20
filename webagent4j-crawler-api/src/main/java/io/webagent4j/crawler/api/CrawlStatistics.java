package io.webagent4j.crawler.api;

/**
 * Immutable crawl-wide counters. Every field has one precise definition - never an ambiguous "count
 * of something."
 *
 * @param discoveredUrls distinct normalized URLs that passed scope/policy and were claimed by the
 *     deduplicator during this crawl (includes the seeds; includes URLs never actually fetched
 *     because {@code maxPages} was reached first)
 * @param fetchedUrls unique URLs for which a fetch attempt was actually started - this is exactly
 *     the quantity {@link CrawlRequest#maxPages()} bounds
 * @param successfulPages number of {@link CrawlResult#pages()} entries; always equal to {@code
 *     pages.size()}
 * @param failedUrls number of {@link CrawlResult#failures()} entries; {@code fetchedUrls} always
 *     equals {@code successfulPages + failedUrls}
 * @param rejectedUrls discovered candidate links that scope policy, depth, or URL filters rejected
 *     before ever being claimed by the deduplicator
 * @param redirects total redirect hops observed across every fetch attempt
 * @param duplicateUrls discovery attempts suppressed because the normalized URL was already claimed
 * @param totalBytes sum of {@code responseBytes} across every fetch attempt that received a
 *     response body, successful or failed
 * @param maxDepthReached the greatest depth among all actually-fetched URLs
 */
public record CrawlStatistics(
        int discoveredUrls,
        int fetchedUrls,
        int successfulPages,
        int failedUrls,
        int rejectedUrls,
        int redirects,
        int duplicateUrls,
        long totalBytes,
        int maxDepthReached) {

    /** Validates that every counter is non-negative. */
    public CrawlStatistics {
        requireNonNegative(discoveredUrls, "discoveredUrls");
        requireNonNegative(fetchedUrls, "fetchedUrls");
        requireNonNegative(successfulPages, "successfulPages");
        requireNonNegative(failedUrls, "failedUrls");
        requireNonNegative(rejectedUrls, "rejectedUrls");
        requireNonNegative(redirects, "redirects");
        requireNonNegative(duplicateUrls, "duplicateUrls");
        requireNonNegative(totalBytes, "totalBytes");
        requireNonNegative(maxDepthReached, "maxDepthReached");
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }
}

package io.webagent4j.crawler.api;

/**
 * Immutable crawl-wide counters. Every field has one precise definition - never an ambiguous "count
 * of something."
 *
 * @param discoveredUrls distinct normalized URLs claimed by navigation/frontier discovery during
 *     this crawl - seeds and links found while parsing a page, never a redirect hop (a redirect
 *     target is a fetch-time identity, not a discovered one; see {@link #fetchedUrls()}). Includes
 *     URLs never actually fetched because {@code maxPages} was reached first
 * @param fetchedUrls distinct normalized URLs for which a real HTTP request was actually started -
 *     every redirect hop actually followed counts as its own unique fetched URL, in addition to
 *     each task's own starting URL. This is exactly the quantity {@link CrawlRequest#maxPages()}
 *     bounds, checked immediately before every one of these requests, including a redirect hop -
 *     never discovered only after the request was already sent. {@code fetchedUrls >=
 *     successfulPages + failedUrls}: a single task consumes one fetched-URL unit for a direct
 *     success/failure, or more than one when it followed one or more redirects first, since only
 *     the task's own final outcome becomes a page or a failure, never each intermediate hop
 * @param successfulPages number of {@link CrawlResult#pages()} entries; always equal to {@code
 *     pages.size()}
 * @param failedUrls number of {@link CrawlResult#failures()} entries; always equal to {@code
 *     failures.size()}
 * @param rejectedUrls discovered candidate links that scope policy, depth, URL filters, or the
 *     deduplicator rejected before ever entering the frontier
 * @param redirects total redirect hops actually followed across every fetch attempt
 * @param duplicateUrls discovery attempts suppressed because the normalized URL was already claimed
 *     by the frontier/discovery deduplicator - a separate concept from a redirect converging onto
 *     an already-<em>fetched</em> identity, which is instead a {@link
 *     CrawlFailureType#ALREADY_FETCHED} {@link CrawlFailure} and does not affect this counter
 * @param totalBytes sum of {@code responseBytes} across every HTTP response that was read in full
 *     (successful or a terminal HTTP status), across every retry and every redirect hop. A response
 *     aborted for exceeding {@link CrawlRequest#maxResponseBytes()} contributes nothing to this
 *     total: the byte count at the moment of abortion is not tracked
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

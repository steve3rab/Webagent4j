package io.webagent4j.crawler.api;

/**
 * Immutable crawl-wide counters. Every field has one precise, independent definition. Deliberately
 * <strong>no</strong> general mathematical relationship is asserted between {@link #fetchedUrls()},
 * {@link #successfulPages()}, and {@link #failedUrls()}: a {@link CrawlFailure} can be recorded for
 * a URL that was never actually requested - {@link CrawlFailureType#CRAWL_LIMIT_REACHED} and {@link
 * CrawlFailureType#ALREADY_FETCHED} both add an entry to {@link CrawlResult#failures()} (and so to
 * {@code failedUrls}) without adding a new fetch identity (so without increasing {@code
 * fetchedUrls}) - so {@code failedUrls} is never derivable from {@code fetchedUrls}, in either
 * direction, in general.
 *
 * @param discoveredUrls distinct normalized URLs admitted into navigation/frontier discovery during
 *     this crawl - every seed and every HTML link accepted by the scope/depth/dedup checks that
 *     gate frontier entry. A redirect target is never a discovered URL, only a fetched one; see
 *     {@link #fetchedUrls()}. Includes URLs enqueued but never actually fetched because {@code
 *     maxPages} was reached first
 * @param fetchedUrls distinct normalized URLs for which at least one real HTTP request was actually
 *     started - every redirect hop actually followed counts as its own fetched URL, in addition to
 *     each task's own starting URL; retries of the same URL never count more than once. This is
 *     exactly the quantity {@link CrawlRequest#maxPages()} bounds, claimed immediately before every
 *     one of these requests, including a redirect hop - never discovered only after the request was
 *     already sent
 * @param successfulPages number of {@link CrawlResult#pages()} entries; always equal to {@code
 *     pages.size()}
 * @param failedUrls number of {@link CrawlResult#failures()} entries; always equal to {@code
 *     failures.size()}. This is a count of crawl-task outcomes that ended in failure, not of
 *     network requests that failed - a failure can be recorded for a URL that was never actually
 *     fetched (see {@link CrawlFailureType#CRAWL_LIMIT_REACHED}, {@link
 *     CrawlFailureType#ALREADY_FETCHED}), so this value must never be assumed derivable from {@code
 *     fetchedUrls}
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
 * @param maxDepthReached the greatest crawl-task depth among tasks for which at least one real HTTP
 *     request was actually started - a task whose fetch identity claim failed before any request
 *     was sent ({@code maxPages} exhausted, or the identity already fetched) never contributes
 *     here, even though it may still produce a {@link CrawlFailure}. Redirect hops followed while
 *     resolving a task never change that task's depth
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

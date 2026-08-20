package io.webagent4j.crawler.api;

import java.util.List;
import java.util.Objects;

/**
 * Immutable outcome of one {@link ICrawler#crawl(CrawlRequest)} call.
 *
 * @param pages every page successfully fetched, in deterministic crawl (fetch) order
 * @param failures every URL whose fetch attempt failed terminally, in the order the failure was
 *     observed
 * @param statistics crawl-wide counters
 * @param rejectedUrls every discovered link that scope policy, depth, {@code maxPages}, or
 *     deduplication rejected before it could be fetched, each carrying its {@link
 *     DiscoveredLink#rejection()} reason
 * @param terminationReason why the crawl loop stopped
 */
public record CrawlResult(
        List<CrawledPage> pages,
        List<CrawlFailure> failures,
        CrawlStatistics statistics,
        List<DiscoveredLink> rejectedUrls,
        CrawlTerminationReason terminationReason) {

    /** Validates required fields and defensively copies every collection. */
    public CrawlResult {
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        Objects.requireNonNull(statistics, "statistics");
        rejectedUrls = List.copyOf(Objects.requireNonNull(rejectedUrls, "rejectedUrls"));
        Objects.requireNonNull(terminationReason, "terminationReason");
    }
}

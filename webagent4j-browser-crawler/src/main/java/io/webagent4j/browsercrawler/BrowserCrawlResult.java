package io.webagent4j.browsercrawler;

import io.webagent4j.crawler.api.DiscoveredLink;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of one {@link IBrowserCrawler#crawl(BrowserCrawlRequest)} call.
 *
 * @param pages every successfully navigated and stabilized page
 * @param failures every claimed navigation that did not succeed
 * @param statistics independent counters describing this crawl
 * @param rejectedUrls every discovered URL the scope policy rejected before navigation
 * @param terminationReason why the crawl stopped
 */
public record BrowserCrawlResult(
        List<BrowserCrawledPage> pages,
        List<BrowserCrawlFailure> failures,
        BrowserCrawlStatistics statistics,
        List<DiscoveredLink> rejectedUrls,
        BrowserCrawlTerminationReason terminationReason) {

    /** Validates fields and defensively copies every list. */
    public BrowserCrawlResult {
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        Objects.requireNonNull(statistics, "statistics");
        rejectedUrls = List.copyOf(Objects.requireNonNull(rejectedUrls, "rejectedUrls"));
        Objects.requireNonNull(terminationReason, "terminationReason");
    }
}

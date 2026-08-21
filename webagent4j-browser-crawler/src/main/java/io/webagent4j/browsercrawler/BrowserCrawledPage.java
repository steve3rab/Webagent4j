package io.webagent4j.browsercrawler;

import io.webagent4j.crawler.api.DiscoveredLink;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One successfully navigated, stabilized page, with the links discovered in its rendered DOM at the
 * moment stability was reached.
 *
 * <p>Unlike {@code CrawledPage} ({@code webagent4j-crawler-api}), this type carries no HTTP
 * response concept - no status code, no header map, no response byte count - because a live,
 * browser-rendered document has no single honest equivalent for any of them. {@link #links()}
 * reuses {@code DiscoveredLink} from {@code webagent4j-crawler-api} directly: a link discovered
 * from a rendered DOM has exactly the same shape as one discovered from parsed static HTML.
 *
 * @param requestedUrl the normalized URL this task was claimed under
 * @param finalUrl the page's committed URL after navigation - equal to {@code requestedUrl} unless
 *     the browser redirected (an HTTP 30x, a JavaScript redirect, or a client-side router change
 *     observed before stability)
 * @param depth this page's BFS depth; seeds are depth {@code 0}
 * @param discoveredFrom the page this URL was discovered from; empty for a seed
 * @param title the page's title at the moment of stability, if the backend reported one
 * @param links every link discovered in the rendered DOM at stability, in document order
 * @param navigationOrder this page's position in deterministic FIFO frontier order - the sequence
 *     assigned once, at claim/enqueue time, by the single thread that runs the whole crawl (see
 *     {@code docs/browser-crawler.md#concurrency-model}); there is no physical navigation
 *     concurrency in this engine for it to stay stable "despite," so this is a structural
 *     guarantee, not one that merely happens to hold
 * @param timeToStability monotonic elapsed duration, measured against {@code WaitBudget}'s clock,
 *     from the start of the navigation attempt until the stability window is satisfied - combined
 *     navigation-plus-stability elapsed time, not stability-only (the same {@code WaitBudget}
 *     starts before {@code navigate()} is called and is never restarted for the stability leg),
 *     which is exactly what makes it directly comparable to {@code navigationTimeout}; excludes any
 *     time spent afterward in {@code page.url()}/{@code page.observe()}/{@code page.title()}, which
 *     are not bounded by any deadline (see {@code docs/browser-crawler.md#navigation-timeout});
 *     excluded from the determinism contract - see {@code
 *     docs/browser-crawler.md#determinism-contract}
 */
public record BrowserCrawledPage(
        URI requestedUrl,
        URI finalUrl,
        int depth,
        Optional<URI> discoveredFrom,
        Optional<String> title,
        List<DiscoveredLink> links,
        int navigationOrder,
        Duration timeToStability) {

    /** Validates fields and defensively copies {@link #links()}. */
    public BrowserCrawledPage {
        Objects.requireNonNull(requestedUrl, "requestedUrl");
        Objects.requireNonNull(finalUrl, "finalUrl");
        Objects.requireNonNull(discoveredFrom, "discoveredFrom");
        Objects.requireNonNull(title, "title");
        links = List.copyOf(Objects.requireNonNull(links, "links"));
        Objects.requireNonNull(timeToStability, "timeToStability");
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0, was " + depth);
        }
        if (navigationOrder < 0) {
            throw new IllegalArgumentException(
                    "navigationOrder must be >= 0, was " + navigationOrder);
        }
        if (timeToStability.isNegative()) {
            throw new IllegalArgumentException("timeToStability must not be negative");
        }
    }
}

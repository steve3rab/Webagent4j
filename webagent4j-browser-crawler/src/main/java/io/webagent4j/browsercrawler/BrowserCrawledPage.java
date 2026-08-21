package io.webagent4j.browsercrawler;

import io.webagent4j.crawler.api.DiscoveredLink;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One successfully navigated, stabilized page, with the links discovered in the rendered DOM during
 * the observation taken immediately after stability was accepted.
 *
 * <p>Unlike {@code CrawledPage} ({@code webagent4j-crawler-api}), this type carries no HTTP
 * response concept - no status code, no header map, no response byte count - because a live,
 * browser-rendered document has no single honest equivalent for any of them. {@link #links()}
 * reuses {@code DiscoveredLink} from {@code webagent4j-crawler-api} directly: a link discovered
 * from a rendered DOM has exactly the same shape as one discovered from parsed static HTML.
 *
 * <p><b>Not an atomic snapshot:</b> {@code page.url()}, {@code page.observe()} (which {@link
 * #links()} is derived from), and {@code page.title()} are three separate, sequential backend calls
 * made immediately after stability is accepted, not one atomic read of browser state. There is a
 * small window between stability acceptance and these calls - and between the calls themselves -
 * during which the page could theoretically mutate or navigate again; Phase 0.7 does not provide an
 * atomic cross-call snapshot, and none of these three values is guaranteed to reflect exactly the
 * same instant as {@link #timeToStability()}'s deadline.
 *
 * @param requestedUrl the normalized URL this task was claimed under
 * @param finalUrl the page's committed URL after navigation - equal to {@code requestedUrl} unless
 *     the browser redirected (an HTTP 30x, a JavaScript redirect, or a client-side router change
 *     observed before stability)
 * @param depth this page's BFS depth; seeds are depth {@code 0}
 * @param discoveredFrom the page this URL was discovered from; empty for a seed
 * @param title the page title, read via {@code page.title()} immediately after the post-stability
 *     observation, if the backend reported one - see the class-level note on non-atomicity
 * @param links links discovered in the rendered DOM during the observation immediately following
 *     accepted stability, in document order - see the class-level note on non-atomicity
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

package io.webagent4j.browsercrawler;

/**
 * A deterministic, bounded-concurrency crawler that discovers and navigates pages through a real
 * browser, for content an HTTP crawler cannot render (JavaScript-rendered links, client-side
 * navigation, authenticated sessions).
 *
 * <p>Deliberately not the same contract as {@code ICrawler} ({@code webagent4j-crawler-api}):
 * {@code ICrawler#crawl(CrawlRequest)} and {@code ICrawlScopePolicy#evaluate(URI, URI,
 * CrawlRequest)} are both bound to the concrete, HTTP-shaped {@code CrawlRequest} record (seed
 * scheme restricted to {@code http}/{@code https} as a transport client, response byte limits,
 * retry-on-status-code policy, and so on) - reusing that contract here would mean forcing browser
 * session/stability/frame/concurrency configuration into fields that do not fit them, exactly what
 * this phase's own design constraints forbid. {@link BrowserCrawler} is the sole implementation of
 * this interface, mirroring how {@code HttpCrawler} is the sole implementation of {@code ICrawler}.
 * See {@code docs/browser-crawler.md} for the full contract.
 */
@FunctionalInterface
public interface IBrowserCrawler {

    /**
     * Runs one crawl to completion and returns its result.
     *
     * <p>Blocks the calling thread until the crawl reaches a {@link BrowserCrawlTerminationReason}.
     * Does not close {@code request}'s browser unless the request explicitly opts into it - see
     * {@code docs/browser-crawler.md#resource-ownership}.
     */
    BrowserCrawlResult crawl(BrowserCrawlRequest request);
}

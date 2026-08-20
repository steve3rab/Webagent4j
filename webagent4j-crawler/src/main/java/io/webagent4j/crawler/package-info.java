/**
 * The deterministic, sequential HTTP crawler engine: a {@code java.net.http.HttpClient} fetcher,
 * jsoup link extraction, a breadth-first frontier, URL normalization/deduplication/scope policy,
 * and redirect/retry handling - no browser, no JavaScript execution.
 *
 * <p>{@link io.webagent4j.crawler.HttpCrawler} is the only implementation of {@code ICrawler}
 * ({@code webagent4j-crawler-api}). It tracks two identity sets deliberately kept separate:
 * discovery identity (seeds and HTML links only) drives {@code CrawlStatistics#discoveredUrls()},
 * while fetch identity (every real HTTP request, including each redirect hop) drives {@code
 * fetchedUrls()} and is exactly what {@code CrawlRequest#maxPages()} bounds, claimed immediately
 * before every real request through one central gate.
 *
 * <p>This package cannot depend on Playwright, any browser backend, or an AI/LLM library - enforced
 * by dedicated ArchUnit rules. See {@code docs/http-crawler.md} for the full traversal pipeline and
 * the precise meaning of every {@code CrawlFailureType}.
 */
package io.webagent4j.crawler;

/**
 * A deterministic, bounded-concurrency browser crawler: JavaScript-rendered link discovery through
 * a real browser session, reusing {@code webagent4j-browser-api} for navigation and {@code
 * webagent4j-wait} for stability rather than a second wait/timing implementation.
 *
 * <p>{@link io.webagent4j.browsercrawler.BrowserCrawler} is the only implementation of {@link
 * io.webagent4j.browsercrawler.IBrowserCrawler} - deliberately a separate contract from {@code
 * ICrawler} ({@code webagent4j-crawler-api}), since that interface and its {@code CrawlRequest} are
 * bound to HTTP-specific fields a browser session/stability/frame/concurrency configuration does
 * not fit. {@link io.webagent4j.browsercrawler.BrowserCrawlRequest} is immutable and fully
 * validated at construction; one {@code IBrowser} instance is the crawl's session (cookies,
 * storage, and authentication state are shared across every page it opens) and is never closed by
 * the crawler unless the request explicitly opts in.
 *
 * <p>This module depends only on backend-neutral contracts ({@code webagent4j-browser-api}, {@code
 * webagent4j-crawler-api}, {@code webagent4j-wait}) - never Playwright directly, enforced by
 * dedicated ArchUnit rules. See {@code docs/browser-crawler.md} for the full navigation, stability,
 * bounded-concurrency, and determinism contract.
 */
package io.webagent4j.browsercrawler;

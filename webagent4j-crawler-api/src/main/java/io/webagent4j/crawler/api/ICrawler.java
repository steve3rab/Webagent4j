package io.webagent4j.crawler.api;

/**
 * Backend-neutral crawler contract. {@code io.webagent4j.crawler.HttpCrawler} (in {@code
 * webagent4j-crawler}) is the only implementation in this phase; a future browser-driven crawler
 * could implement this same contract if its results are genuinely compatible, rather than being
 * forced into it.
 */
@FunctionalInterface
public interface ICrawler {

    /**
     * Runs one crawl to completion and returns its result. Synchronous: this phase introduces no
     * cancellation or asynchronous API.
     */
    CrawlResult crawl(CrawlRequest request);
}

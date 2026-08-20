package io.webagent4j.crawler.api;

import java.net.URI;

/**
 * Decides whether a candidate URL discovered on one page may enter the crawl frontier: scheme,
 * host/domain, and URL-filter rules. Depth, {@code maxPages}, and duplicate-detection outcomes are
 * decided separately by the crawl engine itself, not by this policy.
 */
@FunctionalInterface
public interface ICrawlScopePolicy {

    /**
     * Evaluates {@code candidate}, discovered on {@code source}, against {@code request}'s scope
     * configuration.
     *
     * @param candidate the absolute, not-yet-normalized candidate URL
     * @param source the page {@code candidate} was discovered on
     * @param request the active crawl configuration
     */
    CrawlDecision evaluate(URI candidate, URI source, CrawlRequest request);
}

package io.webagent4j.crawler.api;

/**
 * Every possible outcome of deciding whether a candidate URL enters the crawl frontier. Exactly one
 * value is {@link #ALLOW}; every other value is a distinct, diagnosable rejection reason - never a
 * single opaque boolean.
 */
public enum CrawlDecisionType {

    /** The candidate is allowed to enter the frontier. */
    ALLOW,

    /** The candidate's scheme is not in {@link CrawlRequest#allowedSchemes()}. */
    REJECT_SCHEME,

    /** {@link CrawlRequest#sameHostOnly()} is set and the candidate's host does not match. */
    REJECT_HOST,

    /**
     * {@link CrawlRequest#includeSubdomains()} is set and the candidate's host is neither an
     * allowed host nor one of its true subdomains (a lookalike such as {@code evil-example.com} for
     * {@code example.com} is rejected, never accepted by a bare {@code endsWith} check).
     */
    REJECT_DOMAIN,

    /**
     * The candidate's URL matched {@link CrawlRequest#excludeUrlPatterns()}, or {@link
     * CrawlRequest#includeUrlPatterns()} is non-empty and the candidate matched none of them.
     */
    REJECT_URL_FILTER,

    /** The candidate's depth would exceed {@link CrawlRequest#maxDepth()}. */
    REJECT_DEPTH,

    /** {@link CrawlRequest#maxPages()} unique fetch attempts have already been started. */
    REJECT_MAX_PAGES,

    /** The candidate's normalized URL was already discovered earlier in this crawl. */
    REJECT_DUPLICATE
}

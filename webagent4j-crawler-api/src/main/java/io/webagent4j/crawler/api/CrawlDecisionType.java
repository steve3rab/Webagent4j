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
     * The candidate's URL matched {@link CrawlRequest#excludeUrlPatterns()}, {@link
     * CrawlRequest#includeUrlPatterns()} is non-empty and the candidate matched none of them, or
     * (when at least one such pattern is configured) the candidate URL exceeded the maximum length
     * evaluated against them - a resource bound, not a match outcome, but not worth a distinct type
     * of its own since it is still fundamentally a URL-filter-stage rejection.
     */
    REJECT_URL_FILTER,

    /** The candidate's depth would exceed {@link CrawlRequest#maxDepth()}. */
    REJECT_DEPTH,

    /**
     * {@link CrawlRequest#maxPages()} unique fetch identities have already been claimed at the
     * moment this link was discovered - a proactive, best-effort check: it can still miss a case
     * where the limit is reached by a redirect hop consumed after this link was already enqueued,
     * in which case the authoritative rejection instead surfaces later as a {@link
     * CrawlFailureType#CRAWL_LIMIT_REACHED} {@link CrawlFailure}, never a silently dropped fetch.
     */
    REJECT_MAX_PAGES,

    /** The candidate's normalized URL was already discovered earlier in this crawl. */
    REJECT_DUPLICATE,

    /**
     * The crawl was cancelled before this candidate could be claimed. Cooperative cancellation is
     * not yet implemented by every crawler engine in this project (see {@code
     * io.webagent4j.browsercrawler.CancellationToken} for the one that does), but the reason itself
     * is engine-neutral, so it lives here rather than being duplicated per engine.
     */
    REJECT_CANCELLED
}

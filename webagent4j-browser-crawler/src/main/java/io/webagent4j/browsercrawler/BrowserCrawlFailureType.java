package io.webagent4j.browsercrawler;

/**
 * The reason a single page's navigation did not produce a {@link BrowserCrawledPage}.
 *
 * <p>Deliberately separate from {@code CrawlFailureType} ({@code webagent4j-crawler-api}): most of
 * that taxonomy is HTTP-response-shaped (status codes, redirect hop counts, response byte limits)
 * with no honest browser-navigation equivalent, and collapsing genuinely different browser failure
 * modes into one generic value would violate this project's fail-closed, explicit-failure
 * philosophy. See {@code docs/browser-crawler.md#failure-model} for the full contract.
 */
public enum BrowserCrawlFailureType {

    /**
     * The navigation attempt itself (before the page ever reaches stability - see {@link
     * #PAGE_STABILITY_TIMEOUT}) did not commit within the request's navigation timeout. Classified
     * deterministically from the typed {@link io.webagent4j.browser.NavigationTimeoutException},
     * never inferred from a backend-specific exception message or a timing coincidence - see {@code
     * docs/browser-crawler.md#navigation-timeout}.
     */
    NAVIGATION_TIMEOUT,

    /** The browser backend reported navigation failure for a reason other than a timeout. */
    NAVIGATION_FAILED,

    /** The page never reached the configured stability window within the navigation timeout. */
    PAGE_STABILITY_TIMEOUT,

    /** Navigation committed to a final URL outside the request's scope policy. Never indexed. */
    OUT_OF_SCOPE_REDIRECT,

    /**
     * The page's observation hit a configured capture limit (see {@link
     * io.webagent4j.observation.ObservationStatistics#truncated()}) before it could be captured
     * completely. An incomplete snapshot is never treated as a complete, successful link discovery
     * - see {@code docs/browser-crawler.md#observation-truncation}.
     */
    OBSERVATION_TRUNCATED,

    /**
     * A configured frame could not be resolved or inspected (see {@link FrameCrawlPolicy}).
     * Currently unreachable: only {@link FrameCrawlPolicy#TOP_LEVEL_ONLY} is implemented, so no
     * task ever attempts frame access. Declared now for the same forward-compatibility reason
     * {@link FrameCrawlPolicy#SAME_ORIGIN_FRAMES} is declared but rejected at construction.
     */
    FRAME_ACCESS_FAILED,

    /** The page was closed (by the backend or another actor) before navigation could complete. */
    PAGE_CLOSED,

    /** An opaque backend/runtime failure unrelated to any of the above, typed causes preserved. */
    BROWSER_BACKEND_FAILURE,

    /** The crawl was cancelled before this already-claimed task's navigation began. */
    CANCELLED,

    /**
     * A configured network policy denied this URL before navigation - {@code IPage#navigate} was
     * never called for it.
     */
    NETWORK_POLICY_DENIED,

    /**
     * A configured network policy failed to evaluate for this URL - threw, or returned a malformed
     * {@code null} decision - before navigation. Treated identically to {@link
     * #NETWORK_POLICY_DENIED}: {@code IPage#navigate} was never called for it.
     */
    NETWORK_POLICY_EVALUATION_FAILED,

    /**
     * Navigation genuinely completed, but the final URL the browser landed on - only checkable
     * after the fact, since a browser's own internal redirects cannot be intercepted mid-flight -
     * was denied (or failed to evaluate) by a configured network policy. Unlike {@link
     * #NETWORK_POLICY_DENIED}, {@code IPage#navigate} was called exactly once for this task; the
     * page is still recorded as a failure, with no observation or link discovery performed on it.
     */
    NETWORK_POLICY_VIOLATION
}

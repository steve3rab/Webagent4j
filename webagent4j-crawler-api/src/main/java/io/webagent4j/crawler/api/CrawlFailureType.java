package io.webagent4j.crawler.api;

/** Distinct, diagnosable reasons a fetch attempt failed to produce a {@link CrawledPage}. */
public enum CrawlFailureType {

    /** A connection or I/O failure (connection reset, DNS failure, connection refused). */
    NETWORK,

    /** The request exceeded {@link CrawlRequest#requestTimeout()}. */
    TIMEOUT,

    /** A redirect response's {@code Location} header was missing or not a valid URL. */
    INVALID_REDIRECT,

    /** A redirect chain revisited a URL already seen earlier in the same chain. */
    REDIRECT_LOOP,

    /** A redirect chain exceeded {@link CrawlRequest#maxRedirects()}. */
    TOO_MANY_REDIRECTS,

    /** A terminal {@code 4xx} response. */
    HTTP_CLIENT_ERROR,

    /** A {@code 5xx} response that exhausted its retry budget (or was not retryable). */
    HTTP_SERVER_ERROR,

    /** The response body exceeded {@link CrawlRequest#maxResponseBytes()}. */
    RESPONSE_TOO_LARGE,

    /** The response's {@code Content-Type} is not in {@link CrawlRequest#allowedContentTypes()}. */
    UNSUPPORTED_CONTENT_TYPE,

    /** The response body could not be decoded or parsed as HTML. */
    INVALID_CONTENT,

    /** The candidate URL itself was structurally invalid. */
    INVALID_URL,

    /**
     * A response status outside every other classified range (1xx, or a 3xx this crawler does not
     * treat as a followable redirect, such as {@code 304 Not Modified} - this phase has no HTTP
     * cache, so a {@code 304} cannot produce a usable {@link CrawledPage}). Never folded into
     * {@link #HTTP_SERVER_ERROR}, which is reserved for {@code 5xx}.
     */
    UNEXPECTED_HTTP_STATUS,

    /**
     * {@link CrawlRequest#maxPages()} unique fetch identities were already claimed at the moment
     * this URL needed to be fetched - checked immediately before every real HTTP request, including
     * a redirect hop, never discovered only after the request was already sent.
     */
    CRAWL_LIMIT_REACHED,

    /**
     * This URL's normalized identity was already fetched earlier in the same crawl - reached this
     * time only as a redirect target (or, more rarely, as a task's own seed/discovered URL that a
     * different task's redirect chain happened to reach first). Never fetched a second time; this
     * phase keeps no page cache to reuse, so the outcome is a structured signal rather than a
     * fabricated duplicate {@link CrawledPage}.
     */
    ALREADY_FETCHED,

    /** An opaque, unexpected fetcher failure - never silently reclassified as another type. */
    BACKEND_FAILURE
}

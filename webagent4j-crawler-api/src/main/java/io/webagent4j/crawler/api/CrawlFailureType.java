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

    /** An opaque, unexpected fetcher failure - never silently reclassified as another type. */
    BACKEND_FAILURE
}

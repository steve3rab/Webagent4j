package io.webagent4j.crawler;

import java.io.IOException;

/**
 * Backend-neutral single-hop HTTP fetch abstraction. {@link HttpCrawler} depends on this, never
 * directly on {@code java.net.http.HttpClient}, so a test can inject a fake fetcher without any
 * real network access.
 */
@FunctionalInterface
public interface IHttpFetcher {

    /**
     * Performs one {@code GET} round trip.
     *
     * @throws java.net.http.HttpTimeoutException if {@code request}'s timeout elapsed
     * @throws ResponseTooLargeException if the response body exceeded {@code
     *     request.maxResponseBytes()}
     * @throws IOException for any other transport failure (connection reset, DNS failure, refused
     *     connection)
     */
    HttpFetchResult fetch(HttpFetchRequest request) throws IOException;
}

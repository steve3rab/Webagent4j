package io.webagent4j.crawler;

import io.webagent4j.policy.network.VerifiedNetworkAddresses;
import java.io.IOException;
import java.util.Optional;

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

    /**
     * Performs one {@code GET} round trip, connecting only to an address in {@code pinnedAddresses}
     * when present - never performing its own, independent resolution of {@code request.uri()}'s
     * host that could silently observe a different address than the one a network policy already
     * authorized for this exact connection attempt.
     *
     * <p>The default implementation ignores {@code pinnedAddresses} entirely and delegates to
     * {@link #fetch(HttpFetchRequest)} - exactly today's behavior - so every existing fetcher
     * (including any test fake) keeps working completely unchanged; only a fetcher that overrides
     * this method can offer the pinning guarantee. {@code request.uri()}'s host is still what a
     * caller sends for the request line, {@code Host} header, TLS SNI, and certificate hostname
     * verification even when pinned - the physical address is never a substitute for it there.
     *
     * @throws java.net.http.HttpTimeoutException if {@code request}'s timeout elapsed
     * @throws ResponseTooLargeException if the response body exceeded {@code
     *     request.maxResponseBytes()}
     * @throws IOException for any other transport failure, including every address in {@code
     *     pinnedAddresses} being unreachable
     */
    default HttpFetchResult fetch(
            HttpFetchRequest request, Optional<VerifiedNetworkAddresses> pinnedAddresses)
            throws IOException {
        return fetch(request);
    }
}

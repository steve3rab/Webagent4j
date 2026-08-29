package io.webagent4j.crawler;

import io.webagent4j.crawler.api.internal.HttpHeaderValidation;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable {@code GET} request for one {@link IHttpFetcher#fetch(HttpFetchRequest)} call - one
 * HTTP round trip, never a redirect-following sequence: the caller (the crawl engine) decides
 * whether and how to follow a redirect target, so it can apply crawl scope to it first.
 *
 * @param uri the absolute URL to request
 * @param timeout this request's timeout
 * @param headers request headers, including {@code User-Agent}. Validated against the HTTP/1.1
 *     header grammar at construction time - identically whether or not {@code headers} already
 *     passed through {@link io.webagent4j.crawler.api.CrawlRequest}, since this is also a
 *     network-boundary object that can be constructed independently
 * @param maxResponseBytes the greatest response body size to read before failing the request
 */
public record HttpFetchRequest(
        URI uri, Duration timeout, Map<String, String> headers, long maxResponseBytes) {

    /**
     * Validates required fields, validates every header name/value against the canonical {@link
     * HttpHeaderValidation} rule, and defensively copies the header map.
     */
    public HttpFetchRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(headers, "headers");
        // Copied into an order-preserving map first so validation failure is deterministic
        // (first-failure in the given order) rather than depending on Map.copyOf's unspecified
        // iteration order - the same reasoning CrawlRequest's own header validation follows.
        Map<String, String> orderedHeaders = new LinkedHashMap<>(headers);
        HttpHeaderValidation.requireValidHeaders(orderedHeaders);
        headers = Map.copyOf(orderedHeaders);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
    }
}

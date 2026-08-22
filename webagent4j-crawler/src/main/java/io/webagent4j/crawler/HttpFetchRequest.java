package io.webagent4j.crawler;

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
 * @param headers request headers, including {@code User-Agent}
 * @param maxResponseBytes the greatest response body size to read before failing the request
 */
public record HttpFetchRequest(
        URI uri, Duration timeout, Map<String, String> headers, long maxResponseBytes) {

    /** Validates required fields and defensively copies the header map. */
    public HttpFetchRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        headers = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(headers, "headers")));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
    }
}

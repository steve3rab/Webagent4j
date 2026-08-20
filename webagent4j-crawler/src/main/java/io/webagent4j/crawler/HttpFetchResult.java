package io.webagent4j.crawler;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of one successfully-completed HTTP round trip - a response was received and its
 * body fully read within the request's byte limit. The response's HTTP status code is not
 * interpreted here: a {@code 404} or {@code 500} is still a normal {@link HttpFetchResult}, never a
 * thrown exception - only a genuine transport failure (timeout, I/O error, response too large)
 * fails {@link IHttpFetcher#fetch}.
 *
 * @param requestedUri the URL that was requested
 * @param statusCode the response's HTTP status code
 * @param headers the response headers, preserving multi-value order
 * @param body the raw, undecoded response body
 * @param contentType the raw {@code Content-Type} header value, or {@code ""} if absent
 * @param elapsed monotonic-clock-measured time spent on this one round trip - distinct from {@link
 *     io.webagent4j.crawler.api.CrawledPage#fetchDuration()}, which accumulates every retry and
 *     redirect hop for the whole page, not just one request
 */
public record HttpFetchResult(
        URI requestedUri,
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body,
        String contentType,
        Duration elapsed) {

    /** Validates required fields and defensively copies the header map and body. */
    public HttpFetchResult {
        Objects.requireNonNull(requestedUri, "requestedUri");
        headers = copyHeaders(headers);
        body = Objects.requireNonNull(body, "body").clone();
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(elapsed, "elapsed");
    }

    /** Returns a defensive copy of the response body. */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Returns the response body's size in bytes. */
    public long responseBytes() {
        return body.length;
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
        Objects.requireNonNull(headers, "headers");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Map.copyOf(copy);
    }
}

package io.webagent4j.crawler.api;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, structured record of one URL's fetch attempt failing terminally - never a plain {@code
 * null} or a silently-dropped entry.
 *
 * @param url the URL that failed
 * @param depth the URL's crawl depth
 * @param type the failure category
 * @param message a diagnostic message
 * @param statusCode the HTTP status code, when the failure followed a response
 * @param cause the underlying exception, when the failure originated from one - preserved rather
 *     than discarded
 * @param attempts how many fetch attempts were made for this URL, including the final one
 * @param discoveredFrom the page this URL was discovered on, absent only for a seed
 */
public record CrawlFailure(
        URI url,
        int depth,
        CrawlFailureType type,
        String message,
        Optional<Integer> statusCode,
        Optional<Throwable> cause,
        int attempts,
        Optional<URI> discoveredFrom) {

    /** Validates required fields. */
    public CrawlFailure {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(statusCode, "statusCode");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(discoveredFrom, "discoveredFrom");
        if (depth < 0) {
            throw new IllegalArgumentException("depth cannot be negative");
        }
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least one");
        }
    }
}

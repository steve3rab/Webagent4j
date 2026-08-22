package io.webagent4j.crawler.api;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, structured record of one URL's fetch attempt failing terminally - never a plain {@code
 * null} or a silently-dropped entry.
 *
 * <p>{@code requestedUrl} and {@code failedUrl} are tracked separately because a redirect chain can
 * fail partway through: for {@code requestedUrl == failedUrl} the failure occurred on the very
 * first request, with an empty {@code redirectChain}; for {@code A -> B -> C} failing on {@code C},
 * {@code requestedUrl} is {@code A}, {@code failedUrl} is {@code C}, and {@code redirectChain}
 * contains both hops actually followed before the failure - the failure is never reported as if it
 * happened on {@code A} alone.
 *
 * @param requestedUrl the URL the fetch attempt started from (a seed, or a discovered link's
 *     normalized identity)
 * @param failedUrl the URL actually being fetched at the moment of failure - equal to {@code
 *     requestedUrl} unless one or more redirect hops were followed first
 * @param depth the URL's crawl depth
 * @param type the failure category
 * @param message a diagnostic message
 * @param statusCode the HTTP status code, when the failure followed a response
 * @param cause the underlying exception, when the failure originated from one - preserved for
 *     explicit diagnostics but excluded from {@link #toString()} because backend messages may
 *     contain sensitive external data
 * @param attempts how many real HTTP requests were made for {@code failedUrl}, including the final
 *     one. {@code 0} only for a {@link CrawlFailureType#CRAWL_LIMIT_REACHED} or {@link
 *     CrawlFailureType#ALREADY_FETCHED} outcome, decided before any network call - every other
 *     {@link CrawlFailureType} always sent at least one real request, so {@code attempts >= 1}
 * @param discoveredFrom the page this URL was discovered on, absent only for a seed
 * @param redirectChain every redirect hop actually followed before reaching {@code failedUrl};
 *     empty when the failure occurred on the first request
 */
public record CrawlFailure(
        URI requestedUrl,
        URI failedUrl,
        int depth,
        CrawlFailureType type,
        String message,
        Optional<Integer> statusCode,
        Optional<Throwable> cause,
        int attempts,
        Optional<URI> discoveredFrom,
        List<RedirectHop> redirectChain) {

    /** Validates required fields and defensively copies {@code redirectChain}. */
    public CrawlFailure {
        Objects.requireNonNull(requestedUrl, "requestedUrl");
        Objects.requireNonNull(failedUrl, "failedUrl");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(statusCode, "statusCode");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(discoveredFrom, "discoveredFrom");
        redirectChain = List.copyOf(Objects.requireNonNull(redirectChain, "redirectChain"));
        if (depth < 0) {
            throw new IllegalArgumentException("depth cannot be negative");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts cannot be negative");
        }
        boolean neverSendsARequest =
                type == CrawlFailureType.CRAWL_LIMIT_REACHED
                        || type == CrawlFailureType.ALREADY_FETCHED;
        if (attempts == 0 && !neverSendsARequest) {
            throw new IllegalArgumentException(
                    "attempts == 0 is only valid for CRAWL_LIMIT_REACHED or ALREADY_FETCHED (no"
                            + " real HTTP request was ever sent for those), not "
                            + type);
        }
        if (attempts > 0 && neverSendsARequest) {
            throw new IllegalArgumentException(
                    type
                            + " never sends a real HTTP request, so attempts must be 0, got "
                            + attempts);
        }
    }

    /** Renders only bounded structural diagnostics, excluding URLs, messages, and causes. */
    @Override
    public String toString() {
        return "CrawlFailure[type="
                + type
                + ", depth="
                + depth
                + ", attempts="
                + attempts
                + ", statusCode="
                + statusCode.map(String::valueOf).orElse("-")
                + ", redirectCount="
                + redirectChain.size()
                + "]";
    }
}

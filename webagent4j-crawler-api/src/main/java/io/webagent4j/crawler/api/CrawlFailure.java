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
 *     one. {@code 0} for a {@link CrawlFailureType#CRAWL_LIMIT_REACHED} or {@link
 *     CrawlFailureType#ALREADY_FETCHED} outcome, both of which are always decided before any
 *     network call; every other {@link CrawlFailureType} always sent at least one real request, so
 *     {@code attempts >= 1} - <strong>except</strong> {@link
 *     CrawlFailureType#NETWORK_POLICY_DENIED} and {@link
 *     CrawlFailureType#NETWORK_POLICY_EVALUATION_FAILED}, which can carry either: {@code 0} when
 *     the network policy denied the very first attempt (no request for this URL was ever sent), or
 *     a positive count when one or more retries of an already-permitted URL succeeded in being sent
 *     before a later retry was freshly re-evaluated and denied - every retry is re-authorized
 *     immediately before it is sent, so a denial can happen after real attempts already occurred
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
        // CRAWL_LIMIT_REACHED and ALREADY_FETCHED are decided by the claim gate before
        // fetchWithRetries is ever entered, so they can never carry a positive attempt count.
        // NETWORK_POLICY_DENIED and NETWORK_POLICY_EVALUATION_FAILED are different: every retry is
        // freshly re-authorized immediately before it is sent, so a network-policy failure can
        // happen either before the first attempt (attempts == 0) or after one or more real retries
        // already succeeded in being sent (attempts > 0) - both are valid for those two types.
        boolean alwaysZeroAttempts =
                type == CrawlFailureType.CRAWL_LIMIT_REACHED
                        || type == CrawlFailureType.ALREADY_FETCHED;
        boolean mayHaveZeroAttempts =
                alwaysZeroAttempts
                        || type == CrawlFailureType.NETWORK_POLICY_DENIED
                        || type == CrawlFailureType.NETWORK_POLICY_EVALUATION_FAILED;
        if (attempts == 0 && !mayHaveZeroAttempts) {
            throw new IllegalArgumentException(
                    "attempts == 0 is only valid for CRAWL_LIMIT_REACHED, ALREADY_FETCHED,"
                            + " NETWORK_POLICY_DENIED, or NETWORK_POLICY_EVALUATION_FAILED (no"
                            + " real HTTP request was ever sent for those), not "
                            + type);
        }
        if (attempts > 0 && alwaysZeroAttempts) {
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

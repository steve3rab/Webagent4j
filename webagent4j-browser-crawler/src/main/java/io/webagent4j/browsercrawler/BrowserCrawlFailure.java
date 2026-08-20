package io.webagent4j.browsercrawler;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * A structured, provenance-preserving record of one claimed navigation that did not produce a
 * {@link BrowserCrawledPage}.
 *
 * @param requestedUrl the normalized URL this task was claimed under
 * @param depth this task's BFS depth
 * @param type why navigation did not succeed
 * @param message a human-readable explanation - never the sole diagnostic signal, {@link #type()}
 *     always is
 * @param cause the backend/runtime exception, when one exists
 * @param discoveredFrom the page this URL was discovered from; empty for a seed
 * @param navigationOrder this task's position in deterministic frontier order
 */
public record BrowserCrawlFailure(
        URI requestedUrl,
        int depth,
        BrowserCrawlFailureType type,
        String message,
        Optional<Throwable> cause,
        Optional<URI> discoveredFrom,
        int navigationOrder) {

    /** Validates fields. */
    public BrowserCrawlFailure {
        Objects.requireNonNull(requestedUrl, "requestedUrl");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(discoveredFrom, "discoveredFrom");
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0, was " + depth);
        }
        if (navigationOrder < 0) {
            throw new IllegalArgumentException(
                    "navigationOrder must be >= 0, was " + navigationOrder);
        }
    }
}

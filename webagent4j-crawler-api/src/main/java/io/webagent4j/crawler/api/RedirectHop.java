package io.webagent4j.crawler.api;

import java.net.URI;
import java.util.Objects;

/**
 * One HTTP redirect hop observed while resolving a single fetch attempt.
 *
 * @param from the URL that returned the redirect
 * @param to the URL the redirect pointed to, absolute and already resolved against {@code from}
 * @param statusCode the redirect status code (301, 302, 303, 307, or 308)
 */
public record RedirectHop(URI from, URI to, int statusCode) {

    /** Validates required fields. */
    public RedirectHop {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}

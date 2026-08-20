package io.webagent4j.crawler.api;

import java.net.URI;

/**
 * Deterministically normalizes an absolute URL into the identity used for deduplication.
 *
 * <p>Implementations must be idempotent - {@code normalize(normalize(uri))} must equal {@code
 * normalize(uri)} - and must never depend on network state, wall-clock time, or randomness.
 */
@FunctionalInterface
public interface IUrlNormalizer {

    /**
     * Returns {@code uri}'s normalized form.
     *
     * @throws IllegalArgumentException if {@code uri} is not absolute
     */
    URI normalize(URI uri);
}

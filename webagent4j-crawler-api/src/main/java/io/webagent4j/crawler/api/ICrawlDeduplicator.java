package io.webagent4j.crawler.api;

import java.net.URI;

/**
 * Tracks which normalized URLs have already been discovered during one crawl, so the same URL is
 * never fetched twice.
 */
public interface ICrawlDeduplicator {

    /**
     * Atomically claims {@code normalizedUrl} for this crawl.
     *
     * @return {@code true} if this is the first claim for {@code normalizedUrl} (the caller should
     *     proceed), {@code false} if it was already claimed (the caller must not fetch it again)
     */
    boolean tryClaim(URI normalizedUrl);
}

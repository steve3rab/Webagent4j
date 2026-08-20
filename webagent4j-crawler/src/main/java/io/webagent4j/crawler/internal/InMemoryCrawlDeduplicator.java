package io.webagent4j.crawler.internal;

import io.webagent4j.crawler.api.ICrawlDeduplicator;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@link LinkedHashSet}-backed {@link ICrawlDeduplicator}: sequential, single-crawl, single-thread
 * use only - {@link #tryClaim} is not safe for concurrent callers.
 */
public final class InMemoryCrawlDeduplicator implements ICrawlDeduplicator {

    private final Set<URI> claimed = new LinkedHashSet<>();

    @Override
    public boolean tryClaim(URI normalizedUrl) {
        return claimed.add(Objects.requireNonNull(normalizedUrl, "normalizedUrl"));
    }
}

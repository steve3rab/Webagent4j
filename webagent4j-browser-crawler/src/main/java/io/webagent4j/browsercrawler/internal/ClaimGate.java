package io.webagent4j.browsercrawler.internal;

import io.webagent4j.browsercrawler.BrowserCrawler;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The single, synchronized gate every worker thread claims a navigation identity through.
 *
 * <p>{@code webagent4j-crawler}'s {@code InMemoryCrawlDeduplicator} is explicitly documented as
 * "sequential, single-crawl, single-thread use only" - correct for the sequential HTTP engine, but
 * not reusable here, since {@link BrowserCrawler} claims identities from multiple worker threads
 * concurrently. This type makes "already claimed" and "{@code maxPages} reached" one atomic
 * decision (a plain {@code synchronized} block over a {@link LinkedHashSet}, not a lock-free CAS
 * trick - correctness matters far more than throughput for a budget this small), exactly the
 * invariant {@code docs/browser-crawler.md#url-identities-and-deduplication} documents: two
 * concurrent discoveries of the same normalized URL can never both navigate, and {@code maxPages}
 * can never be exceeded no matter how many links are discovered at once.
 */
public final class ClaimGate {

    /** The outcome of one claim attempt. */
    public enum Outcome {
        CLAIMED,
        ALREADY_CLAIMED,
        LIMIT_REACHED
    }

    private final Object lock = new Object();
    private final Set<URI> claimed = new LinkedHashSet<>();
    private final int maxPages;

    public ClaimGate(int maxPages) {
        this.maxPages = maxPages;
    }

    public Outcome tryClaim(URI normalizedUrl) {
        synchronized (lock) {
            if (claimed.contains(normalizedUrl)) {
                return Outcome.ALREADY_CLAIMED;
            }
            if (claimed.size() >= maxPages) {
                return Outcome.LIMIT_REACHED;
            }
            claimed.add(normalizedUrl);
            return Outcome.CLAIMED;
        }
    }

    public int claimedCount() {
        synchronized (lock) {
            return claimed.size();
        }
    }
}

package io.webagent4j.browsercrawler.internal;

import io.webagent4j.browsercrawler.BrowserCrawler;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The single, synchronized gate every worker thread claims a navigation identity through.
 *
 * <p>{@link BrowserCrawler} itself only ever calls this from its single execution-lane thread (see
 * {@code docs/browser-crawler.md#concurrency-model}), so the {@code synchronized} block here is
 * defense-in-depth rather than a requirement of the current architecture - {@code
 * webagent4j-crawler}'s {@code InMemoryCrawlDeduplicator} is explicitly documented as "sequential,
 * single-crawl, single-thread use only" and would be equally correct for that architecture, but
 * this type is kept independently thread-safe and stress-tested as such so a future caller cannot
 * misuse it from more than one thread by surprise. This type makes "already claimed" and "{@code
 * maxPages} reached" one atomic decision (a plain {@code synchronized} block over a {@link
 * LinkedHashSet}, not a lock-free CAS trick - correctness matters far more than throughput for a
 * budget this small), exactly the invariant {@code
 * docs/browser-crawler.md#url-identities-and-deduplication} documents: the same normalized URL can
 * never be claimed twice, and {@code maxPages} can never be exceeded no matter how many links are
 * discovered at once.
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

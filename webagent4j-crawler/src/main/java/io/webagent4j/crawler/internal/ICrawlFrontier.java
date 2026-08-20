package io.webagent4j.crawler.internal;

import java.util.Optional;

/** Deterministically orders pending {@link CrawlTask}s for the crawl loop to process. */
public interface ICrawlFrontier {

    /** Adds one task to the frontier. */
    void enqueue(CrawlTask task);

    /** Removes and returns the next task to process, or empty when the frontier has none left. */
    Optional<CrawlTask> poll();

    /** Returns whether the frontier currently has no pending tasks. */
    boolean isEmpty();
}

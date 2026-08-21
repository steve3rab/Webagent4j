package io.webagent4j.browsercrawler.internal;

import io.webagent4j.browsercrawler.BrowserCrawler;
import java.util.ArrayDeque;
import java.util.Optional;

/**
 * A plain FIFO frontier, touched only by the single coordinator thread.
 *
 * <p>Mirrors {@code BreadthFirstCrawlFrontier} in {@code webagent4j-crawler}: because tasks are
 * only ever enqueued while draining an already-dequeued task's discovered links (appended after
 * every already-queued same-or-lower-depth task), plain FIFO order is already breadth-first order -
 * no per-depth bucketing is needed. Deliberately never backed by a hash-based collection.
 *
 * <p>Public because it lives in an {@code internal} package by convention, not by Java-enforced
 * access - {@link BrowserCrawler} in the parent package must be able to call it, exactly as {@code
 * BreadthFirstCrawlFrontier} is public in {@code io.webagent4j.crawler.internal}. Not part of the
 * module's supported public API; see {@code package-info.java}.
 */
public final class BrowserCrawlFrontier {

    private final ArrayDeque<BrowserCrawlTask> tasks = new ArrayDeque<>();

    public void enqueue(BrowserCrawlTask task) {
        tasks.addLast(task);
    }

    public Optional<BrowserCrawlTask> poll() {
        return Optional.ofNullable(tasks.pollFirst());
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }
}

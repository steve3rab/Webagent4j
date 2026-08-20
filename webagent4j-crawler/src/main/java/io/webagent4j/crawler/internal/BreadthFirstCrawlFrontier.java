package io.webagent4j.crawler.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/**
 * FIFO frontier: tasks are polled in exactly the order they were enqueued. Because the crawl loop
 * only discovers depth-N+1 tasks while processing a depth-N task (appending them after every
 * already-queued depth-N task), plain FIFO order is already breadth-first order - no separate
 * per-depth bucketing is needed. Backed by {@link ArrayDeque}, never a {@link java.util.HashSet} or
 * {@link java.util.HashMap} whose iteration order is not insertion order.
 */
public final class BreadthFirstCrawlFrontier implements ICrawlFrontier {

    private final Deque<CrawlTask> queue = new ArrayDeque<>();

    @Override
    public void enqueue(CrawlTask task) {
        queue.addLast(Objects.requireNonNull(task, "task"));
    }

    @Override
    public Optional<CrawlTask> poll() {
        return Optional.ofNullable(queue.pollFirst());
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }
}

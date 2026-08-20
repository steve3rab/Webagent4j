package io.webagent4j.crawler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BreadthFirstCrawlFrontierTest {

    private static final URI SEED = URI.create("https://example.test/");

    @Test
    void pollsTasksInExactEnqueueOrder() {
        BreadthFirstCrawlFrontier frontier = new BreadthFirstCrawlFrontier();
        CrawlTask first = task("https://example.test/a", 0);
        CrawlTask second = task("https://example.test/b", 0);
        CrawlTask third = task("https://example.test/c", 1);

        frontier.enqueue(first);
        frontier.enqueue(second);
        frontier.enqueue(third);

        assertThat(frontier.poll()).contains(first);
        assertThat(frontier.poll()).contains(second);
        assertThat(frontier.poll()).contains(third);
        assertThat(frontier.poll()).isEmpty();
    }

    @Test
    void isEmptyReflectsCurrentState() {
        BreadthFirstCrawlFrontier frontier = new BreadthFirstCrawlFrontier();
        assertThat(frontier.isEmpty()).isTrue();

        frontier.enqueue(task("https://example.test/a", 0));
        assertThat(frontier.isEmpty()).isFalse();

        frontier.poll();
        assertThat(frontier.isEmpty()).isTrue();
    }

    @Test
    void childrenDiscoveredWhileProcessingADepthAreOrderedAfterEveryPeerOfTheSameDepth() {
        BreadthFirstCrawlFrontier frontier = new BreadthFirstCrawlFrontier();
        CrawlTask depth0A = task("https://example.test/a", 0);
        CrawlTask depth0B = task("https://example.test/b", 0);
        frontier.enqueue(depth0A);
        frontier.enqueue(depth0B);

        // Simulate the crawl loop: polling depth0A discovers a depth-1 child before depth0B is
        // polled.
        frontier.poll();
        CrawlTask depth1Child = task("https://example.test/a/child", 1);
        frontier.enqueue(depth1Child);

        assertThat(frontier.poll()).contains(depth0B);
        assertThat(frontier.poll()).contains(depth1Child);
    }

    private static CrawlTask task(String url, int depth) {
        return new CrawlTask(URI.create(url), depth, SEED, Optional.empty());
    }
}

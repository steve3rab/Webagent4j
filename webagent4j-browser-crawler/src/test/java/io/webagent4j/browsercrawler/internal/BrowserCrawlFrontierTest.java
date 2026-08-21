package io.webagent4j.browsercrawler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BrowserCrawlFrontierTest {

    @Test
    void isEmptyOnCreation() {
        assertThat(new BrowserCrawlFrontier().isEmpty()).isTrue();
    }

    @Test
    void pollReturnsTasksInFifoOrder() {
        BrowserCrawlFrontier frontier = new BrowserCrawlFrontier();
        BrowserCrawlTask first = task(0);
        BrowserCrawlTask second = task(1);
        BrowserCrawlTask third = task(2);
        frontier.enqueue(first);
        frontier.enqueue(second);
        frontier.enqueue(third);

        assertThat(frontier.poll()).contains(first);
        assertThat(frontier.poll()).contains(second);
        assertThat(frontier.poll()).contains(third);
        assertThat(frontier.poll()).isEmpty();
    }

    @Test
    void interleavedEnqueueAndPollPreservesBfsOrder() {
        BrowserCrawlFrontier frontier = new BrowserCrawlFrontier();
        frontier.enqueue(task(0));
        frontier.enqueue(task(1));
        BrowserCrawlTask polled = frontier.poll().orElseThrow();
        assertThat(polled.sequence()).isEqualTo(0);
        frontier.enqueue(task(2)); // discovered while processing task 0, appended after task 1
        assertThat(frontier.poll().orElseThrow().sequence()).isEqualTo(1);
        assertThat(frontier.poll().orElseThrow().sequence()).isEqualTo(2);
    }

    private static BrowserCrawlTask task(long sequence) {
        URI url = URI.create("https://example.com/" + sequence);
        return new BrowserCrawlTask(sequence, url, 0, url, Optional.empty());
    }
}

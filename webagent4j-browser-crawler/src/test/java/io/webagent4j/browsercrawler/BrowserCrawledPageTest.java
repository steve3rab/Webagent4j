package io.webagent4j.browsercrawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BrowserCrawledPageTest {

    private static final URI URL = URI.create("https://example.com/");

    @Test
    void negativeDepthRejected() {
        assertThatThrownBy(
                        () ->
                                new BrowserCrawledPage(
                                        URL,
                                        URL,
                                        -1,
                                        Optional.empty(),
                                        Optional.empty(),
                                        List.of(),
                                        0,
                                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeStabilityElapsedRejected() {
        assertThatThrownBy(
                        () ->
                                new BrowserCrawledPage(
                                        URL,
                                        URL,
                                        0,
                                        Optional.empty(),
                                        Optional.empty(),
                                        List.of(),
                                        0,
                                        Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linksListIsDefensivelyCopied() {
        var mutable = new java.util.ArrayList<io.webagent4j.crawler.api.DiscoveredLink>();
        BrowserCrawledPage page =
                new BrowserCrawledPage(
                        URL, URL, 0, Optional.empty(), Optional.empty(), mutable, 0, Duration.ZERO);
        mutable.add(null);
        assertThat(page.links()).isEmpty();
    }
}

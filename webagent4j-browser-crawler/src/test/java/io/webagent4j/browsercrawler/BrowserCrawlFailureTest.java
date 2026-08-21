package io.webagent4j.browsercrawler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BrowserCrawlFailureTest {

    private static final URI URL = URI.create("https://example.com/");

    @Test
    void negativeDepthRejected() {
        assertThatThrownBy(
                        () ->
                                new BrowserCrawlFailure(
                                        URL,
                                        -1,
                                        BrowserCrawlFailureType.NAVIGATION_FAILED,
                                        "boom",
                                        Optional.empty(),
                                        Optional.empty(),
                                        0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullTypeRejected() {
        assertThatThrownBy(
                        () ->
                                new BrowserCrawlFailure(
                                        URL,
                                        0,
                                        null,
                                        "boom",
                                        Optional.empty(),
                                        Optional.empty(),
                                        0))
                .isInstanceOf(NullPointerException.class);
    }
}

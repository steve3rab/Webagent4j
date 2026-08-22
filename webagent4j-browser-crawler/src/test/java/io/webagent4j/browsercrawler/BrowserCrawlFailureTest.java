package io.webagent4j.browsercrawler;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void stringRenderingExcludesExternalUrlsMessagesAndCauses() {
        String sensitive = "credential-value-918273";
        URI sensitiveUrl = URI.create("https://example.test/?token=" + sensitive);
        BrowserCrawlFailure failure =
                new BrowserCrawlFailure(
                        sensitiveUrl,
                        0,
                        BrowserCrawlFailureType.NAVIGATION_FAILED,
                        "failure " + sensitive,
                        Optional.of(new IllegalStateException(sensitive)),
                        Optional.empty(),
                        0);

        assertThat(failure.toString())
                .contains("type=NAVIGATION_FAILED")
                .doesNotContain(sensitive)
                .doesNotContain(sensitiveUrl.toString());
    }
}

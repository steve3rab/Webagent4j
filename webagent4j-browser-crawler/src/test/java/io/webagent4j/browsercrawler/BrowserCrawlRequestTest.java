package io.webagent4j.browsercrawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.webagent4j.browser.IBrowser;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BrowserCrawlRequestTest {

    private final IBrowser browser = mock(IBrowser.class);

    @Test
    void validRequestBuildsWithDefaults() {
        BrowserCrawlRequest request =
                BrowserCrawlRequest.builder(browser).seed("https://example.com/").build();

        assertThat(request.seeds()).containsExactly(URI.create("https://example.com/"));
        assertThat(request.maxDepth()).isEqualTo(3);
        assertThat(request.maxPages()).isEqualTo(50);
        assertThat(request.maxConcurrency()).isEqualTo(1);
        assertThat(request.frameCrawlPolicy()).isEqualTo(FrameCrawlPolicy.TOP_LEVEL_ONLY);
        assertThat(request.closeBrowserOnCompletion()).isFalse();
        assertThat(request.cancellationToken().isCancelled()).isFalse();
    }

    @Test
    void nullBrowserRejected() {
        assertThatThrownBy(
                        () ->
                                BrowserCrawlRequest.builder(null)
                                        .seed("https://example.com/")
                                        .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptySeedsRejected() {
        assertThatThrownBy(() -> BrowserCrawlRequest.builder(browser).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seed");
    }

    @Test
    void nonHttpSeedRejected() {
        assertThatThrownBy(
                        () ->
                                BrowserCrawlRequest.builder(browser)
                                        .seed("ftp://example.com/")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void relativeSeedRejected() {
        assertThatThrownBy(() -> BrowserCrawlRequest.builder(browser).seed("/relative").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeMaxDepthRejected() {
        assertThatThrownBy(
                        () ->
                                BrowserCrawlRequest.builder(browser)
                                        .seed("https://example.com/")
                                        .maxDepth(-1)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroMaxPagesRejected() {
        assertThatThrownBy(
                        () ->
                                BrowserCrawlRequest.builder(browser)
                                        .seed("https://example.com/")
                                        .maxPages(0)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroMaxConcurrencyRejected() {
        assertThatThrownBy(
                        () ->
                                BrowserCrawlRequest.builder(browser)
                                        .seed("https://example.com/")
                                        .maxConcurrency(0)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * IBrowser/IPage are not documented as thread-safe (see their Javadoc), and one IBrowser
     * instance is the crawl session, so this engine only ever supports a single navigation lane -
     * see docs/browser-crawler.md#concurrency-model and the regression this guards against
     * (BrowserCrawlerIT's former boundedConcurrencyCompletesTheSameCrawlAsSequential failure, where
     * concurrent navigation against one shared Playwright browser silently lost a page).
     */
    @Test
    void maxConcurrencyGreaterThanOneIsRejected() {
        assertThatThrownBy(
                        () ->
                                BrowserCrawlRequest.builder(browser)
                                        .seed("https://example.com/")
                                        .maxConcurrency(3)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 1");
    }

    @Test
    void zeroNavigationTimeoutRejected() {
        assertThatThrownBy(
                        () ->
                                BrowserCrawlRequest.builder(browser)
                                        .seed("https://example.com/")
                                        .navigationTimeout(Duration.ZERO)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroStabilityWindowRejected() {
        assertThatThrownBy(
                        () ->
                                BrowserCrawlRequest.builder(browser)
                                        .seed("https://example.com/")
                                        .stabilityWindow(Duration.ZERO)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameOriginFramesRejectedInThisPhase() {
        assertThatThrownBy(
                        () ->
                                BrowserCrawlRequest.builder(browser)
                                        .seed("https://example.com/")
                                        .frameCrawlPolicy(FrameCrawlPolicy.SAME_ORIGIN_FRAMES)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not yet implemented");
    }

    @Test
    void allAccessibleFramesRejectedInThisPhase() {
        assertThatThrownBy(
                        () ->
                                BrowserCrawlRequest.builder(browser)
                                        .seed("https://example.com/")
                                        .frameCrawlPolicy(FrameCrawlPolicy.ALL_ACCESSIBLE_FRAMES)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}

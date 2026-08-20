package io.webagent4j.crawler.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CrawlRequestTest {

    @Test
    void buildsWithDocumentedDefaults() {
        CrawlRequest request = CrawlRequest.builder().seed("https://example.test/").build();

        assertThat(request.seeds()).containsExactly(URI.create("https://example.test/"));
        assertThat(request.maxDepth()).isEqualTo(3);
        assertThat(request.maxPages()).isEqualTo(100);
        assertThat(request.sameHostOnly()).isTrue();
        assertThat(request.includeSubdomains()).isFalse();
        assertThat(request.allowedSchemes()).containsExactlyInAnyOrder("http", "https");
        assertThat(request.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(request.maxResponseBytes()).isEqualTo(5_000_000L);
        assertThat(request.maxRedirects()).isEqualTo(5);
        assertThat(request.userAgent()).isEqualTo(CrawlRequest.DEFAULT_USER_AGENT);
        assertThat(request.allowedContentTypes())
                .containsExactlyInAnyOrder("text/html", "application/xhtml+xml");
        assertThat(request.traversalStrategy()).isEqualTo(TraversalStrategy.BREADTH_FIRST);
        assertThat(request.failFast()).isFalse();
    }

    @Test
    void preservesMultipleSeedsInInsertionOrder() {
        CrawlRequest request =
                CrawlRequest.builder().seed("https://a.test/").seed("https://b.test/").build();

        assertThat(request.seeds())
                .containsExactly(URI.create("https://a.test/"), URI.create("https://b.test/"));
    }

    @Test
    void rejectsNoSeeds() {
        assertThatIllegalArgumentException().isThrownBy(() -> CrawlRequest.builder().build());
    }

    @Test
    void rejectsNonHttpSeed() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CrawlRequest.builder().seed("ftp://example.test/").build());
    }

    @Test
    void rejectsRelativeSeed() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CrawlRequest.builder().seed("/relative").build());
    }

    @Test
    void rejectsNegativeMaxDepth() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .maxDepth(-1)
                                        .build());
    }

    @Test
    void rejectsNonPositiveMaxPages() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .maxPages(0)
                                        .build());
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .timeout(Duration.ZERO)
                                        .build());
    }

    @Test
    void rejectsNonPositiveMaxResponseBytes() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .maxResponseBytes(0)
                                        .build());
    }

    @Test
    void rejectsNegativeMaxRedirects() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .maxRedirects(-1)
                                        .build());
    }

    @Test
    void rejectsDepthFirstTraversalAsNotYetImplemented() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .traversalStrategy(TraversalStrategy.DEPTH_FIRST)
                                        .build());
    }

    @Test
    void rejectsEmptyAllowedSchemes() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .allowedSchemes()
                                        .build());
    }

    @Test
    void everyConfigurationErrorIsDiscoveredAtBuildTimeNotMidCrawl() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .maxDepth(-5)
                                        .build());
    }
}

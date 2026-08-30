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
    void allowedSchemesRejectsFtp() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .allowedSchemes("http", "ftp")
                                        .build());
    }

    @Test
    void allowedSchemesAcceptsHttpHttpsCaseInsensitively() {
        CrawlRequest request =
                CrawlRequest.builder()
                        .seed("https://example.test/")
                        .allowedSchemes("HTTP", "HTTPS")
                        .build();

        assertThat(request.allowedSchemes()).containsExactlyInAnyOrder("http", "https");
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

    // --- HTTP-HDR-001: caller-supplied defaultHeaders are validated at build() -----------------

    @Test
    void acceptsAWellFormedDefaultHeader() {
        CrawlRequest request =
                CrawlRequest.builder()
                        .seed("https://example.test/")
                        .defaultHeader("X-Custom-Header", "value123")
                        .build();

        assertThat(request.defaultHeaders()).containsEntry("X-Custom-Header", "value123");
    }

    @Test
    void rejectsADefaultHeaderNameContainingAColon() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .defaultHeader("X-Test:Injected", "value")
                                        .build());
    }

    @Test
    void rejectsADefaultHeaderValueContainingACrlfInjectionPayloadAndNeverEchoesIt() {
        String injectionPayload = "value" + "\r\n" + "X-Injected: yes";
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .defaultHeader("X-Evil", injectionPayload)
                                        .build())
                .withMessageNotContaining(injectionPayload)
                .withMessageNotContaining("X-Injected");
    }

    @Test
    void rejectsFrameworkControlledDefaultHeadersCaseInsensitively() {
        for (String name :
                new String[] {
                    "Host",
                    "Connection",
                    "Content-Length",
                    "Transfer-Encoding",
                    "Expect",
                    "Upgrade",
                    "host",
                    "CONNECTION"
                }) {
            assertThatIllegalArgumentException()
                    .as("header: %s", name)
                    .isThrownBy(
                            () ->
                                    CrawlRequest.builder()
                                            .seed("https://example.test/")
                                            .defaultHeader(name, "anything")
                                            .build());
        }
    }

    @Test
    void rejectsAnInvalidUserAgentHeaderValue() {
        // userAgent flows into the User-Agent header (see HttpCrawler), so it is held to the same
        // grammar as defaultHeaders - not just a non-blank check.
        String injectionPayload = "MyBot" + "\r\n" + "X-Injected: yes";
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .userAgent(injectionPayload)
                                        .build())
                .withMessageNotContaining(injectionPayload);
    }

    // --- determinism: multiple invalid defaultHeaders fail on the first, in insertion order ----

    @Test
    void multipleInvalidDefaultHeadersFailDeterministicallyOnTheFirstOneAdded() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("https://example.test/")
                                        .defaultHeader("X-First-Bad", "value" + "\r\n" + "injected")
                                        .defaultHeader("Host", "evil.example.test")
                                        .build())
                .withMessageContaining("header value contains a character forbidden");
    }

    // --- DIAG-URL-001: seed exception messages never expose the full URI -----------------------

    @Test
    void urlDiag001SeedExceptionNeverExposesUserinfo() {
        String diagnosticSentinel = "DIAGNOSTIC-SENTINEL-604817";
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("ftp://" + diagnosticSentinel + "@example.test/")
                                        .build())
                .withMessageNotContaining(diagnosticSentinel);
    }

    @Test
    void urlDiag002SeedExceptionNeverExposesAQueryToken() {
        String diagnosticSentinel = "DIAGNOSTIC_SENTINEL_471182";
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("ftp://example.test/?token=" + diagnosticSentinel)
                                        .build())
                .withMessageNotContaining(diagnosticSentinel);
    }

    @Test
    void urlDiag003SeedExceptionNeverExposesAFragmentSecret() {
        String diagnosticSentinel = "DIAGNOSTIC_SENTINEL_735204";
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed("ftp://example.test/#" + diagnosticSentinel)
                                        .build())
                .withMessageNotContaining(diagnosticSentinel);
    }

    @Test
    void relativeSeedExceptionNeverExposesQueryOrFragment() {
        String diagnosticSentinel = "DIAGNOSTIC_SENTINEL_286650";
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                CrawlRequest.builder()
                                        .seed(
                                                "/relative?x="
                                                        + diagnosticSentinel
                                                        + "#"
                                                        + diagnosticSentinel)
                                        .build())
                .withMessageNotContaining(diagnosticSentinel);
    }
}

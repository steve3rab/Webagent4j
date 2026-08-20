package io.webagent4j.crawler.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.common.RetryPolicy;
import io.webagent4j.crawler.HttpCrawler;
import io.webagent4j.crawler.api.CrawlDecisionType;
import io.webagent4j.crawler.api.CrawlFailureType;
import io.webagent4j.crawler.api.CrawlRequest;
import io.webagent4j.crawler.api.CrawlResult;
import io.webagent4j.crawler.api.CrawlTerminationReason;
import io.webagent4j.crawler.api.CrawledPage;
import io.webagent4j.crawler.api.QueryParameterPolicy;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end scenarios HTTP-001 through HTTP-020: a real {@link HttpCrawler} (real {@code
 * java.net.http.HttpClient}, real jsoup parsing) against a real, deterministic local HTTP server -
 * no browser, no external network, no fake collaborators.
 */
class HttpCrawlerIT {

    private static HttpCrawlerTestServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpCrawlerTestServer.start();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    private static CrawlRequest.Builder request(String seedRoute) {
        return CrawlRequest.builder().seed(server.url(seedRoute)).timeout(Duration.ofSeconds(5));
    }

    private static CrawlResult crawl(CrawlRequest.Builder request) {
        return new HttpCrawler().crawl(request.build());
    }

    // HTTP-001: seed page linking to two same-host pages, all fetched.
    @Test
    void seedPageWithTwoSimpleLinksYieldsThreePages() {
        CrawlResult result = crawl(request("/h1/").maxDepth(1));

        assertThat(result.pages()).hasSize(3);
        assertThat(result.pages())
                .extracting(page -> page.requestedUrl().getPath())
                .containsExactlyInAnyOrder("/h1/", "/h1/about", "/h1/products");
        assertThat(result.terminationReason()).isEqualTo(CrawlTerminationReason.COMPLETED);
    }

    // HTTP-002: relative, same-directory, and root-relative hrefs all resolve correctly.
    @Test
    void relativeHrefsResolveAgainstTheirDocumentsDirectory() {
        CrawlResult result = crawl(request("/h2/a/index.html").maxDepth(1).sameHostOnly(true));

        assertThat(result.pages())
                .extracting(page -> page.requestedUrl().getPath())
                .containsExactlyInAnyOrder("/h2/a/index.html", "/h2/b", "/h2/a/c", "/root");
    }

    // HTTP-003: fragment-only variants of the same URL dedup to a single fetch.
    @Test
    void fragmentOnlyVariantsDedupToASingleFetch() {
        CrawlResult result = crawl(request("/h3/seed").maxDepth(1));

        long pageFetches =
                result.pages().stream()
                        .filter(page -> page.requestedUrl().getPath().equals("/h3/page"))
                        .count();
        assertThat(pageFetches).isEqualTo(1);
        assertThat(result.statistics().duplicateUrls()).isEqualTo(2);
    }

    // HTTP-004: a dot-segment URL and its already-clean equivalent dedup to one fetch.
    @Test
    void dotSegmentUrlDedupsWithItsCleanEquivalent() {
        CrawlResult result = crawl(request("/h4/seed").maxDepth(1));

        long productFetches =
                result.pages().stream()
                        .filter(page -> page.requestedUrl().getPath().equals("/h4/products"))
                        .count();
        assertThat(productFetches).isEqualTo(1);
    }

    // HTTP-005: an external-host link is rejected and never fetched under sameHostOnly.
    @Test
    void anExternalHostLinkIsRejectedAndNeverFetched() {
        CrawlResult result = crawl(request("/h5/seed").maxDepth(1).sameHostOnly(true));

        assertThat(result.pages())
                .noneMatch(page -> page.requestedUrl().getHost().equals("external.test"));
        assertThat(result.rejectedUrls())
                .anySatisfy(
                        link ->
                                assertThat(link.rejection())
                                        .map(decision -> decision.type())
                                        .contains(CrawlDecisionType.REJECT_HOST));
    }

    // HTTP-006: a maxDepth of 2 truncates a 0..3 depth chain before the depth-3 page.
    @Test
    void maxDepthTruncatesTheChainBeforeTheExcessDepthPage() {
        CrawlResult result = crawl(request("/h6/d0").maxDepth(2));

        assertThat(result.pages())
                .extracting(page -> page.requestedUrl().getPath())
                .containsExactlyInAnyOrder("/h6/d0", "/h6/d1", "/h6/d2");
        assertThat(result.pages())
                .noneMatch(page -> page.requestedUrl().getPath().equals("/h6/d3"));
    }

    // HTTP-007: maxPages bounds the number of URLs claimed, on a graph larger than the limit.
    @Test
    void maxPagesBoundsTheCrawlOnAGraphLargerThanTheLimit() {
        CrawlResult result = crawl(request("/h7/seed").maxDepth(1).maxPages(3));

        assertThat(result.statistics().fetchedUrls()).isEqualTo(3);
        assertThat(result.terminationReason()).isEqualTo(CrawlTerminationReason.MAX_PAGES_REACHED);
    }

    // HTTP-008: requestedUrl, finalUrl, and a two-hop redirect chain are all recorded.
    @Test
    void aRedirectChainRecordsRequestedFinalUrlAndEveryHop() {
        CrawlResult result = crawl(request("/h8/a").maxDepth(0));

        assertThat(result.pages()).hasSize(1);
        CrawledPage page = result.pages().get(0);
        assertThat(page.requestedUrl().getPath()).isEqualTo("/h8/a");
        assertThat(page.finalUrl().getPath()).isEqualTo("/h8/final");
        assertThat(page.redirectChain()).hasSize(2);
    }

    // HTTP-009: a redirect loop is a structured failure, never a hang.
    @Test
    void aRedirectLoopIsAStructuredFailure() {
        CrawlResult result = crawl(request("/h9/a").maxDepth(0));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.REDIRECT_LOOP);
    }

    // HTTP-010: a terminal 404 is a structured failure with the status code preserved.
    @Test
    void aNotFoundIsAStructuredFailureWithTheStatusCodePreserved() {
        CrawlResult result = crawl(request("/h10/missing").maxDepth(0));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.HTTP_CLIENT_ERROR);
        assertThat(result.failures().get(0).statusCode()).contains(404);
    }

    // HTTP-011: a 500 followed by a 200 is retried within a bounded retry budget.
    @Test
    void aRetryableServerErrorIsRetriedWithinTheBoundedBudget() {
        CrawlRequest.Builder builder =
                request("/h11/flaky")
                        .maxDepth(0)
                        .retryPolicy(
                                new RetryPolicy(
                                        2, Duration.ofMillis(20), 1.0, Duration.ofMillis(50)));

        CrawlResult result = crawl(builder);

        assertThat(result.pages()).hasSize(1);
        assertThat(server.callCount("/h11/flaky")).isEqualTo(2);
    }

    // HTTP-012: a response slower than the request timeout is a structured timeout failure.
    @Test
    void aSlowResponseIsAStructuredTimeoutFailure() {
        CrawlRequest.Builder builder =
                CrawlRequest.builder()
                        .seed(server.url("/h12/slow"))
                        .timeout(Duration.ofMillis(50))
                        .maxDepth(0)
                        .retryPolicy(
                                new RetryPolicy(
                                        1, Duration.ofMillis(1), 1.0, Duration.ofMillis(1)));

        CrawlResult result = crawl(builder);

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.TIMEOUT);
    }

    // HTTP-013: a response over the configured byte limit is rejected without ever fully buffering.
    @Test
    void aResponseOverTheByteLimitIsRejected() {
        CrawlRequest.Builder builder = request("/h13/huge").maxDepth(0).maxResponseBytes(1_000);

        CrawlResult result = crawl(builder);

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.RESPONSE_TOO_LARGE);
    }

    // HTTP-014: a non-HTML content type is skipped, never parsed as a page.
    @Test
    void aNonHtmlContentTypeIsSkippedRatherThanParsed() {
        CrawlResult result = crawl(request("/h14/image.png").maxDepth(0));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures().get(0).type())
                .isEqualTo(CrawlFailureType.UNSUPPORTED_CONTENT_TYPE);
    }

    // HTTP-015: real UTF-8 accented text and a non-breaking space survive the round trip.
    @Test
    void unicodeContentSurvivesCharsetDetectionAndDecoding() {
        CrawlResult result = crawl(request("/h15/unicode").maxDepth(0));

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).html()).contains("Caf\u00e9").contains("M\u00fcnchen");
    }

    // HTTP-016: a relative href resolves against a <base href>, not the document's own URL.
    @Test
    void aRelativeHrefResolvesAgainstBaseHref() {
        CrawlResult result = crawl(request("/h16/page").maxDepth(0));

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).links()).hasSize(1);
        assertThat(result.pages().get(0).links().get(0).resolvedUrl().getPath())
                .isEqualTo("/h16/catalog/item");
    }

    // HTTP-017: a relative declared canonical URL is resolved to an absolute URL.
    @Test
    void aRelativeDeclaredCanonicalUrlIsResolved() {
        CrawlResult result = crawl(request("/h17/page").maxDepth(0));

        assertThat(result.pages().get(0).declaredCanonicalUrl())
                .map(URI::getPath)
                .contains("/h17/canonical-path");
    }

    // HTTP-018: a tracked and an untracked link to the same target dedup under the tracking policy.
    @Test
    void trackingQueryParameterVariantsDedupUnderTheDropKnownTrackingPolicy() {
        CrawlRequest.Builder builder =
                request("/h18/seed")
                        .maxDepth(1)
                        .queryParameterPolicy(QueryParameterPolicy.dropKnownTracking());

        CrawlResult result = crawl(builder);

        long targetFetches =
                result.pages().stream()
                        .filter(page -> page.requestedUrl().getPath().equals("/h18/target"))
                        .count();
        assertThat(targetFetches).isEqualTo(1);
        assertThat(result.statistics().duplicateUrls()).isEqualTo(1);
    }

    // HTTP-019: mailto/javascript links are rejected diagnostically and never enter the frontier.
    @Test
    void mailtoAndJavascriptLinksAreRejectedRatherThanFetched() {
        CrawlResult result = crawl(request("/h19/seed").maxDepth(1));

        assertThat(result.pages()).hasSize(2);
        assertThat(result.rejectedUrls())
                .extracting(link -> link.rejection().map(decision -> decision.type()).orElseThrow())
                .containsOnly(CrawlDecisionType.REJECT_SCHEME);
        assertThat(result.rejectedUrls()).hasSize(2);
    }

    // HTTP-020: malformed markup (unclosed tags, uppercase, unquoted href) does not fail the page.
    @Test
    void malformedMarkupIsToleratedRatherThanFailingThePage() {
        CrawlResult result = crawl(request("/h20/malformed").maxDepth(1));

        assertThat(result.failures()).isEmpty();
        assertThat(result.pages())
                .extracting(page -> page.requestedUrl().getPath())
                .containsExactlyInAnyOrder("/h20/malformed", "/h20/ok", "/h20/second");
    }
}

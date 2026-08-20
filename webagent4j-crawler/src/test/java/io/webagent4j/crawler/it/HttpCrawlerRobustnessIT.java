package io.webagent4j.crawler.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.crawler.HostScopePolicy;
import io.webagent4j.crawler.HttpCrawler;
import io.webagent4j.crawler.IHttpFetcher;
import io.webagent4j.crawler.JsoupHtmlLinkExtractor;
import io.webagent4j.crawler.api.CrawlDecisionType;
import io.webagent4j.crawler.api.CrawlFailureType;
import io.webagent4j.crawler.api.CrawlRequest;
import io.webagent4j.crawler.api.CrawlResult;
import io.webagent4j.crawler.api.CrawlTerminationReason;
import io.webagent4j.crawler.api.CrawledPage;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Robustness scenarios CRAWL-001 through CRAWL-010, plus the specification's mandated full
 * end-to-end scenario: pathological and adversarial graphs a real crawl must survive without
 * hanging, exploding memory, or silently misclassifying a failure.
 */
class HttpCrawlerRobustnessIT {

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

    // CRAWL-001: a cyclic graph A -> B -> C -> A must terminate rather than loop forever.
    @Test
    void aCyclicGraphTerminatesWithoutAnInfiniteLoop() {
        CrawlResult result = crawl(request("/c1/a").maxDepth(5));

        assertThat(result.pages())
                .extracting(page -> page.requestedUrl().getPath())
                .containsExactlyInAnyOrder("/c1/a", "/c1/b", "/c1/c");
        assertThat(result.statistics().duplicateUrls()).isEqualTo(1);
        assertThat(result.terminationReason()).isEqualTo(CrawlTerminationReason.COMPLETED);
    }

    // CRAWL-002: the same link repeated a hundred times on one page fetches its target once.
    @Test
    void aHundredIdenticalLinksFetchTheirTargetOnce() {
        CrawlResult result = crawl(request("/c2/seed").maxDepth(1));

        assertThat(result.pages()).hasSize(2);
        assertThat(result.statistics().duplicateUrls()).isEqualTo(99);
    }

    // CRAWL-003: fragment, dot-segment, and scheme-case variants all dedup to one identity.
    @Test
    void fragmentDotSegmentAndSchemeCaseVariantsDedupToOneIdentity() {
        CrawlResult result = crawl(request("/c3/seed").maxDepth(1));

        assertThat(result.pages()).hasSize(2);
        assertThat(result.statistics().duplicateUrls()).isEqualTo(3);
    }

    // CRAWL-004: a redirect to an external host is never followed under sameHostOnly.
    @Test
    void aRedirectToAnExternalHostIsNeverFollowedUnderSameHostOnly() {
        CrawlResult result = crawl(request("/c4/seed").maxDepth(0).sameHostOnly(true));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.INVALID_REDIRECT);
    }

    // CRAWL-005: thousands of duplicate links on one page must not explode the frontier.
    @Test
    void thousandsOfDuplicateLinksDoNotExplodeTheFrontier() {
        CrawlResult result = crawl(request("/c5/seed").maxDepth(1));

        assertThat(result.pages()).hasSize(2);
        assertThat(result.statistics().duplicateUrls()).isEqualTo(2_999);
    }

    // CRAWL-006: an unparsable href does not crash extraction of the rest of the page.
    @Test
    void anUnparsableHrefDoesNotCrashTheWholePage() {
        CrawlResult result = crawl(request("/c6/seed").maxDepth(1));

        assertThat(result.failures()).isEmpty();
        assertThat(result.pages())
                .extracting(page -> page.requestedUrl().getPath())
                .containsExactlyInAnyOrder("/c6/seed", "/c6/ok", "/c6/ok2");
    }

    // CRAWL-007: a very large body, well beyond the configured limit, is rejected without hanging.
    @Test
    void aVeryLargeBodyIsRejectedWithoutBufferingItFully() {
        CrawlResult result = crawl(request("/c7/huge").maxDepth(0).maxResponseBytes(2_000));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.RESPONSE_TOO_LARGE);
    }

    // CRAWL-008: a redirect chain of exactly maxRedirects hops succeeds; one hop more fails.
    @Test
    void aRedirectChainOfExactlyMaxRedirectsSucceeds() {
        CrawlResult result = crawl(request("/c8/r0").maxDepth(0).maxRedirects(2));

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).redirectChain()).hasSize(2);
    }

    @Test
    void oneHopBeyondMaxRedirectsFails() {
        CrawlResult result = crawl(request("/c8/r0").maxDepth(0).maxRedirects(1));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.TOO_MANY_REDIRECTS);
    }

    // CRAWL-009: the server closing the connection with no response is a visible NETWORK failure.
    @Test
    void anUnexpectedConnectionCloseIsAVisibleNetworkFailure() {
        CrawlResult result = crawl(request("/c9/reset").maxDepth(0));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.NETWORK);
    }

    // CRAWL-010: an opaque backend exception never silently becomes another failure type.
    @Test
    void anOpaqueBackendExceptionNeverSilentlyBecomesAnotherFailureType() {
        IHttpFetcher brokenFetcher =
                request -> {
                    throw new IllegalStateException("simulated backend bug");
                };
        HttpCrawler crawler =
                new HttpCrawler(
                        brokenFetcher,
                        new JsoupHtmlLinkExtractor(),
                        new HostScopePolicy(),
                        duration -> {});

        CrawlResult result = crawler.crawl(request("/anything").maxDepth(0).build());

        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.BACKEND_FAILURE);
        assertThat(result.failures().get(0).cause()).isPresent();
    }

    /**
     * Section 72's mandated end-to-end scenario: a small graph mixing successful pages, a redirect,
     * a terminal server failure, a duplicate via fragment, a duplicate self-link, an external link,
     * and a mailto link - verified against every documented statistic, not just page count.
     */
    @Test
    void theFullEndToEndScenarioProducesExactlyTheDocumentedResult() {
        CrawlResult result = crawl(request("/e2e/").maxDepth(2).maxPages(10));

        assertThat(result.terminationReason()).isEqualTo(CrawlTerminationReason.COMPLETED);
        assertThat(result.pages())
                .extracting(page -> page.requestedUrl().getPath())
                .containsExactly("/e2e/", "/e2e/products", "/e2e/about", "/e2e/products/1");
        assertThat(result.pages()).extracting(CrawledPage::depth).containsExactly(0, 1, 1, 2);
        CrawledPage aboutPage = result.pages().get(2);
        assertThat(aboutPage.finalUrl().getPath()).isEqualTo("/e2e/company");
        assertThat(aboutPage.redirectChain()).hasSize(1);

        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).url().getPath()).isEqualTo("/e2e/products/2");
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.HTTP_SERVER_ERROR);

        // rejectedUrls() carries every non-allowed link, scope rejections and duplicate
        // rejections alike: REJECT_HOST (the external link), REJECT_SCHEME (the mailto link), and
        // REJECT_DUPLICATE twice ("#featured" re-visiting /e2e/products, and the self-link back to
        // /e2e/ from the products page).
        assertThat(result.rejectedUrls())
                .extracting(link -> link.rejection().map(decision -> decision.type()).orElseThrow())
                .containsExactlyInAnyOrder(
                        CrawlDecisionType.REJECT_HOST,
                        CrawlDecisionType.REJECT_SCHEME,
                        CrawlDecisionType.REJECT_DUPLICATE,
                        CrawlDecisionType.REJECT_DUPLICATE);

        assertThat(result.statistics().discoveredUrls()).isEqualTo(5);
        assertThat(result.statistics().fetchedUrls()).isEqualTo(5);
        assertThat(result.statistics().successfulPages()).isEqualTo(4);
        assertThat(result.statistics().failedUrls()).isEqualTo(1);
        assertThat(result.statistics().rejectedUrls()).isEqualTo(4);
        assertThat(result.statistics().redirects()).isEqualTo(1);
        assertThat(result.statistics().duplicateUrls()).isEqualTo(2);
    }
}

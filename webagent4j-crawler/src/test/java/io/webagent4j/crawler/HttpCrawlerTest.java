package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.crawler.api.CrawlDecisionType;
import io.webagent4j.crawler.api.CrawlFailure;
import io.webagent4j.crawler.api.CrawlFailureType;
import io.webagent4j.crawler.api.CrawlRequest;
import io.webagent4j.crawler.api.CrawlResult;
import io.webagent4j.crawler.api.CrawlTerminationReason;
import io.webagent4j.crawler.api.CrawledPage;
import io.webagent4j.crawler.api.LinkKind;
import io.webagent4j.wait.IMonotonicClock;
import io.webagent4j.wait.IWaitSleeper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link HttpCrawler}'s crawl loop end to end through fake collaborators - no real
 * network, no real sleeping - so every scenario runs deterministically and instantly.
 */
class HttpCrawlerTest {

    private static final URI SEED = URI.create("https://example.test/");

    @Test
    void fetchesOnlyTheSeedWhenMaxDepthIsZero() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(SEED, htmlResponse(SEED, 200));
        FakeHtmlLinkExtractor extractor = new FakeHtmlLinkExtractor();
        extractor.stub(SEED, title("Home"));

        CrawlResult result =
                crawl(fetcher, extractor, CrawlRequest.builder().seed(SEED).maxDepth(0));

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).title()).contains("Home");
        assertThat(result.terminationReason()).isEqualTo(CrawlTerminationReason.COMPLETED);
        assertThat(result.statistics().fetchedUrls()).isEqualTo(1);
        assertThat(result.statistics().successfulPages()).isEqualTo(1);
    }

    @Test
    void followsDiscoveredLinksInBreadthFirstOrderUpToMaxDepth() {
        URI about = URI.create("https://example.test/about");
        URI deep = URI.create("https://example.test/deep");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(SEED, htmlResponse(SEED, 200));
        fetcher.respond(about, htmlResponse(about, 200));
        FakeHtmlLinkExtractor extractor = new FakeHtmlLinkExtractor();
        extractor.stub(SEED, linksTo(about));
        extractor.stub(about, linksTo(deep));

        CrawlResult result =
                crawl(fetcher, extractor, CrawlRequest.builder().seed(SEED).maxDepth(1));

        assertThat(result.pages())
                .extracting(CrawledPage::requestedUrl)
                .containsExactly(SEED, about);
        assertThat(result.pages()).extracting(CrawledPage::depth).containsExactly(0, 1);
        assertThat(result.rejectedUrls()).hasSize(1);
        assertThat(result.rejectedUrls().get(0).rejection())
                .map(decision -> decision.type())
                .contains(CrawlDecisionType.REJECT_DEPTH);
        assertThat(fetcher.requested()).doesNotContain(deep);
    }

    @Test
    void deduplicatesTheSameNormalizedUrlDiscoveredTwice() {
        URI a = URI.create("https://example.test/a");
        URI aWithFragment = URI.create("https://example.test/a#top");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(SEED, htmlResponse(SEED, 200));
        fetcher.respond(a, htmlResponse(a, 200));
        FakeHtmlLinkExtractor extractor = new FakeHtmlLinkExtractor();
        extractor.stub(SEED, linksTo(a, aWithFragment));

        CrawlResult result =
                crawl(fetcher, extractor, CrawlRequest.builder().seed(SEED).maxDepth(1));

        assertThat(result.pages()).hasSize(2);
        assertThat(result.statistics().duplicateUrls()).isEqualTo(1);
        assertThat(fetcher.requested()).containsExactly(SEED, a);
    }

    @Test
    void maxPagesBoundsTheNumberOfUrlsClaimedNotJustSuccessfulPages() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(SEED, htmlResponse(SEED, 200));
        fetcher.respond(a, htmlResponse(a, 200));
        FakeHtmlLinkExtractor extractor = new FakeHtmlLinkExtractor();
        extractor.stub(SEED, linksTo(a, b));

        CrawlResult result =
                crawl(
                        fetcher,
                        extractor,
                        CrawlRequest.builder().seed(SEED).maxDepth(1).maxPages(2));

        assertThat(result.statistics().fetchedUrls()).isEqualTo(2);
        assertThat(result.terminationReason()).isEqualTo(CrawlTerminationReason.MAX_PAGES_REACHED);
        assertThat(fetcher.requested()).doesNotContain(b);
    }

    @Test
    void followsARedirectChainAndRecordsRequestedAndFinalUrls() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        URI finalUrl = URI.create("https://example.test/final");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(a, redirectResponse(a, 301, b));
        fetcher.respond(b, redirectResponse(b, 302, finalUrl));
        fetcher.respond(finalUrl, htmlResponse(finalUrl, 200));
        FakeHtmlLinkExtractor extractor = new FakeHtmlLinkExtractor();
        extractor.stub(finalUrl, title("Final"));

        CrawlResult result = crawl(fetcher, extractor, CrawlRequest.builder().seed(a).maxDepth(0));

        assertThat(result.pages()).hasSize(1);
        CrawledPage page = result.pages().get(0);
        assertThat(page.requestedUrl()).isEqualTo(a);
        assertThat(page.finalUrl()).isEqualTo(finalUrl);
        assertThat(page.redirectChain()).hasSize(2);
        assertThat(result.statistics().redirects()).isEqualTo(2);
    }

    @Test
    void detectsARedirectLoopAsAStructuredFailure() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(a, redirectResponse(a, 302, b));
        fetcher.respond(b, redirectResponse(b, 302, a));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(a).maxDepth(0));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.REDIRECT_LOOP);
    }

    @Test
    void exceedingMaxRedirectsIsAStructuredFailureNotAnInfiniteFollow() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        URI c = URI.create("https://example.test/c");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(a, redirectResponse(a, 302, b));
        fetcher.respond(b, redirectResponse(b, 302, c));
        fetcher.respond(c, htmlResponse(c, 200));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(a).maxDepth(0).maxRedirects(1));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.TOO_MANY_REDIRECTS);
    }

    @Test
    void aTerminalNotFoundIsRecordedAsAStructuredFailureWithoutRetry() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(
                SEED, new HttpFetchResult(SEED, 404, Map.of(), new byte[0], "", Duration.ZERO));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(SEED).maxDepth(0));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.HTTP_CLIENT_ERROR);
        assertThat(result.failures().get(0).statusCode()).contains(404);
        assertThat(fetcher.fetchCount(SEED)).isEqualTo(1);
    }

    @Test
    void retriesARetryableServerErrorThenSucceedsWithoutAnyRealSleep() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(
                SEED, new HttpFetchResult(SEED, 500, Map.of(), new byte[0], "", Duration.ZERO));
        fetcher.respond(SEED, htmlResponse(SEED, 200));
        FakeHtmlLinkExtractor extractor = new FakeHtmlLinkExtractor();
        extractor.stub(SEED, title("Recovered"));
        RecordingWaitSleeper sleeper = new RecordingWaitSleeper();

        CrawlRequest request = CrawlRequest.builder().seed(SEED).maxDepth(0).build();
        HttpCrawler crawler = new HttpCrawler(fetcher, extractor, new HostScopePolicy(), sleeper);
        CrawlResult result = crawler.crawl(request);

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).title()).contains("Recovered");
        assertThat(fetcher.fetchCount(SEED)).isEqualTo(2);
        assertThat(sleeper.sleeps()).hasSize(1);
    }

    @Test
    void anUnsupportedContentTypeIsSkippedRatherThanParsedAsHtml() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(
                SEED,
                new HttpFetchResult(
                        SEED, 200, Map.of(), new byte[] {1, 2, 3}, "image/png", Duration.ZERO));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(SEED).maxDepth(0));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures().get(0).type())
                .isEqualTo(CrawlFailureType.UNSUPPORTED_CONTENT_TYPE);
    }

    @Test
    void anOpaqueFetcherExceptionNeverSilentlyBecomesAnotherFailureType() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.throwRuntime(SEED, new IllegalStateException("boom"));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(SEED).maxDepth(0));

        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.BACKEND_FAILURE);
        assertThat(result.failures().get(0).cause()).isPresent();
    }

    @Test
    void aTimeoutIsAStructuredFailure() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.throwIo(SEED, new HttpTimeoutException("timed out"));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(SEED).maxDepth(0));

        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.TIMEOUT);
    }

    @Test
    void failFastStopsTheCrawlOnTheFirstFailureAndNeverFetchesLaterSeeds() {
        URI secondSeed = URI.create("https://second.test/");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(
                SEED, new HttpFetchResult(SEED, 404, Map.of(), new byte[0], "", Duration.ZERO));
        fetcher.respond(secondSeed, htmlResponse(secondSeed, 200));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder()
                                .seed(SEED)
                                .seed(secondSeed)
                                .sameHostOnly(false)
                                .maxDepth(0)
                                .failFast(true));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.terminationReason()).isEqualTo(CrawlTerminationReason.FATAL_ERROR);
        assertThat(fetcher.requested()).doesNotContain(secondSeed);
    }

    @Test
    void aLinkToAnExternalHostIsRejectedAndNeverFetchedWhenSameHostOnly() {
        URI external = URI.create("https://external.test/");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(SEED, htmlResponse(SEED, 200));
        FakeHtmlLinkExtractor extractor = new FakeHtmlLinkExtractor();
        extractor.stub(SEED, linksTo(external));

        CrawlResult result =
                crawl(
                        fetcher,
                        extractor,
                        CrawlRequest.builder().seed(SEED).sameHostOnly(true).maxDepth(1));

        assertThat(fetcher.requested()).doesNotContain(external);
        assertThat(result.rejectedUrls()).hasSize(1);
        assertThat(result.rejectedUrls().get(0).rejection())
                .map(decision -> decision.type())
                .contains(CrawlDecisionType.REJECT_HOST);
        CrawledPage seedPage = result.pages().get(0);
        assertThat(seedPage.links()).hasSize(1);
        assertThat(seedPage.links().get(0).allowed()).isFalse();
    }

    // --- Redirect identity, maxPages-during-redirect, attempts, and determinism (consolidation)
    // ---

    @Test
    void maxPagesOfOneAllowsTheSeedButBlocksItsOwnRedirectHopExplicitly() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(a, redirectResponse(a, 302, b));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(a).maxDepth(0).maxPages(1));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.CRAWL_LIMIT_REACHED);
        assertThat(result.failures().get(0).failedUrl()).isEqualTo(b);
        assertThat(result.failures().get(0).attempts()).isZero();
        assertThat(result.terminationReason()).isEqualTo(CrawlTerminationReason.MAX_PAGES_REACHED);
        assertThat(fetcher.requested()).containsExactly(a);
    }

    @Test
    void maxPagesOfTwoAllowsTwoHopsButBlocksAThird() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        URI c = URI.create("https://example.test/c");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(a, redirectResponse(a, 302, b));
        fetcher.respond(b, redirectResponse(b, 302, c));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(a).maxDepth(0).maxPages(2));

        assertThat(result.pages()).isEmpty();
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.CRAWL_LIMIT_REACHED);
        assertThat(result.failures().get(0).failedUrl()).isEqualTo(c);
        assertThat(fetcher.requested()).containsExactly(a, b);
    }

    @Test
    void maxPagesExactlyEqualToTheChainLengthSucceeds() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(a, redirectResponse(a, 302, b));
        fetcher.respond(b, htmlResponse(b, 200));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(a).maxDepth(0).maxPages(2));

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).finalUrl()).isEqualTo(b);
        assertThat(result.terminationReason()).isEqualTo(CrawlTerminationReason.COMPLETED);
    }

    @Test
    void aRedirectTargetAlreadyFetchedByAnotherTaskIsNeverFetchedTwice() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        URI target = URI.create("https://example.test/target");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(a, redirectResponse(a, 302, target));
        fetcher.respond(b, redirectResponse(b, 302, target));
        fetcher.respond(target, htmlResponse(target, 200));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(a).seed(b).maxDepth(0));

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).requestedUrl()).isEqualTo(a);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.ALREADY_FETCHED);
        assertThat(result.failures().get(0).requestedUrl()).isEqualTo(b);
        assertThat(result.failures().get(0).failedUrl()).isEqualTo(target);
        assertThat(fetcher.fetchCount(target)).isEqualTo(1);
    }

    @Test
    void aRedirectTargetIsNormalizedBeforeItIsClaimedOrFetched() {
        URI a = URI.create("https://example.test/a");
        URI target = URI.create("https://example.test/target");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(
                a,
                new HttpFetchResult(
                        a,
                        302,
                        Map.of("Location", List.of("https://EXAMPLE.test/target#ignored")),
                        new byte[0],
                        "",
                        Duration.ZERO));
        fetcher.respond(target, htmlResponse(target, 200));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(a).maxDepth(0));

        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).finalUrl()).isEqualTo(target);
        assertThat(fetcher.requested()).containsExactly(a, target);
    }

    @Test
    void aFailureReportsTheActualRedirectTargetNotTheOriginalRequest() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(a, redirectResponse(a, 302, b));
        fetcher.respond(b, new HttpFetchResult(b, 503, Map.of(), new byte[0], "", Duration.ZERO));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(a).maxDepth(0).retryableStatusCodes());

        assertThat(result.failures()).hasSize(1);
        CrawlFailure failure = result.failures().get(0);
        assertThat(failure.requestedUrl()).isEqualTo(a);
        assertThat(failure.failedUrl()).isEqualTo(b);
        assertThat(failure.redirectChain()).hasSize(1);
        assertThat(failure.redirectChain().get(0).from()).isEqualTo(a);
        assertThat(failure.redirectChain().get(0).to()).isEqualTo(b);
    }

    @Test
    void retryAttemptsAreExactlyPreservedForATerminalServerError() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        HttpFetchResult serverError =
                new HttpFetchResult(SEED, 500, Map.of(), new byte[0], "", Duration.ZERO);
        fetcher.respond(SEED, serverError);
        fetcher.respond(SEED, serverError);
        fetcher.respond(SEED, serverError);

        CrawlRequest request =
                CrawlRequest.builder()
                        .seed(SEED)
                        .maxDepth(0)
                        .retryPolicy(
                                new io.webagent4j.common.RetryPolicy(
                                        3, Duration.ZERO, 1.0, Duration.ZERO))
                        .build();
        HttpCrawler crawler =
                new HttpCrawler(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        new HostScopePolicy(),
                        duration -> {});
        CrawlResult result = crawler.crawl(request);

        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.HTTP_SERVER_ERROR);
        assertThat(result.failures().get(0).attempts()).isEqualTo(3);
        assertThat(fetcher.fetchCount(SEED)).isEqualTo(3);
    }

    @Test
    void retryAttemptsAreExactlyPreservedForARepeatedTimeout() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.throwIo(SEED, new HttpTimeoutException("timed out"));
        fetcher.throwIo(SEED, new HttpTimeoutException("timed out"));
        fetcher.throwIo(SEED, new HttpTimeoutException("timed out"));

        CrawlRequest request =
                CrawlRequest.builder()
                        .seed(SEED)
                        .maxDepth(0)
                        .retryPolicy(
                                new io.webagent4j.common.RetryPolicy(
                                        3, Duration.ZERO, 1.0, Duration.ZERO))
                        .build();
        HttpCrawler crawler =
                new HttpCrawler(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        new HostScopePolicy(),
                        duration -> {});
        CrawlResult result = crawler.crawl(request);

        assertThat(result.failures().get(0).type()).isEqualTo(CrawlFailureType.TIMEOUT);
        assertThat(result.failures().get(0).attempts()).isEqualTo(3);
    }

    @Test
    void fetchedUrlsCountsEveryRedirectHopAsItsOwnNetworkTargetWhileDiscoveredUrlsDoesNot() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(a, redirectResponse(a, 302, b));
        fetcher.respond(b, htmlResponse(b, 200));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(a).maxDepth(0));

        assertThat(result.statistics().fetchedUrls()).isEqualTo(2);
        assertThat(result.statistics().discoveredUrls()).isEqualTo(1);
    }

    @Test
    void totalBytesSumsEveryRetryAndRedirectResponseBody() {
        URI a = URI.create("https://example.test/a");
        URI b = URI.create("https://example.test/b");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(
                a, new HttpFetchResult(a, 500, Map.of(), bodyOfSize(100), "", Duration.ZERO));
        fetcher.respond(
                a, new HttpFetchResult(a, 500, Map.of(), bodyOfSize(100), "", Duration.ZERO));
        fetcher.respond(
                a,
                new HttpFetchResult(
                        a,
                        302,
                        Map.of("Location", List.of(b.toString())),
                        bodyOfSize(10),
                        "",
                        Duration.ZERO));
        fetcher.respond(
                b,
                new HttpFetchResult(b, 200, Map.of(), bodyOfSize(500), "text/html", Duration.ZERO));

        CrawlRequest request =
                CrawlRequest.builder()
                        .seed(a)
                        .maxDepth(0)
                        .retryPolicy(
                                new io.webagent4j.common.RetryPolicy(
                                        3, Duration.ZERO, 1.0, Duration.ZERO))
                        .build();
        HttpCrawler crawler =
                new HttpCrawler(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        new HostScopePolicy(),
                        duration -> {});
        CrawlResult result = crawler.crawl(request);

        assertThat(result.statistics().totalBytes()).isEqualTo(710L);
    }

    @Test
    void anUnexpectedHttpStatusIsNeverMisclassifiedAsAServerError() {
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(
                SEED, new HttpFetchResult(SEED, 304, Map.of(), new byte[0], "", Duration.ZERO));

        CrawlResult result =
                crawl(
                        fetcher,
                        new FakeHtmlLinkExtractor(),
                        CrawlRequest.builder().seed(SEED).maxDepth(0));

        assertThat(result.failures().get(0).type())
                .isEqualTo(CrawlFailureType.UNEXPECTED_HTTP_STATUS);
    }

    @Test
    void theSameScriptedCrawlProducesAnIdenticalResultEveryTime() {
        CrawlResult first = runScriptedDeterminismCrawl();
        CrawlResult second = runScriptedDeterminismCrawl();

        assertThat(first).isEqualTo(second);
        assertThat(first.pages()).hasSize(2);
        assertThat(first.pages().get(1).redirectChain()).hasSize(1);
        assertThat(first.statistics().duplicateUrls()).isEqualTo(1);
    }

    private static CrawlResult runScriptedDeterminismCrawl() {
        URI a = URI.create("https://example.test/a");
        URI aFinal = URI.create("https://example.test/a-final");
        URI aWithFragment = URI.create("https://example.test/a#dup");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respond(SEED, htmlResponse(SEED, 200));
        fetcher.respond(a, new HttpFetchResult(a, 500, Map.of(), new byte[0], "", Duration.ZERO));
        fetcher.respond(a, redirectResponse(a, 302, aFinal));
        fetcher.respond(aFinal, htmlResponse(aFinal, 200));
        FakeHtmlLinkExtractor extractor = new FakeHtmlLinkExtractor();
        extractor.stub(SEED, linksTo(a, aWithFragment));
        extractor.stub(aFinal, title("A Final"));

        HttpCrawler crawler =
                new HttpCrawler(
                        fetcher,
                        extractor,
                        new HostScopePolicy(),
                        duration -> {},
                        (IMonotonicClock) (() -> 0L));
        return crawler.crawl(CrawlRequest.builder().seed(SEED).maxDepth(1).build());
    }

    private static byte[] bodyOfSize(int size) {
        byte[] body = new byte[size];
        java.util.Arrays.fill(body, (byte) 'a');
        return body;
    }

    private static CrawlResult crawl(
            IHttpFetcher fetcher, IHtmlLinkExtractor extractor, CrawlRequest.Builder request) {
        HttpCrawler crawler =
                new HttpCrawler(fetcher, extractor, new HostScopePolicy(), duration -> {});
        return crawler.crawl(request.build());
    }

    private static LinkExtractionResult title(String title) {
        return new LinkExtractionResult(List.of(), Optional.of(title), Optional.empty());
    }

    private static LinkExtractionResult linksTo(URI... targets) {
        List<ExtractedLink> links = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) {
            links.add(
                    new ExtractedLink(
                            targets[i],
                            targets[i].toString(),
                            Optional.empty(),
                            LinkKind.ANCHOR,
                            i));
        }
        return new LinkExtractionResult(links, Optional.empty(), Optional.empty());
    }

    private static HttpFetchResult htmlResponse(URI uri, int status) {
        return new HttpFetchResult(
                uri,
                status,
                Map.of("Content-Type", List.of("text/html; charset=utf-8")),
                "<html></html>".getBytes(StandardCharsets.UTF_8),
                "text/html; charset=utf-8",
                Duration.ZERO);
    }

    private static HttpFetchResult redirectResponse(URI uri, int status, URI location) {
        return new HttpFetchResult(
                uri,
                status,
                Map.of("Location", List.of(location.toString())),
                new byte[0],
                "",
                Duration.ZERO);
    }

    /** Records every sleep request without ever actually parking a thread. */
    private static final class RecordingWaitSleeper implements IWaitSleeper {

        private final List<Duration> sleeps = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
        }

        List<Duration> sleeps() {
            return sleeps;
        }
    }

    /**
     * Queues one or more responses per URL: a single-entry queue is replayed for every subsequent
     * call to the same URL (steady state), while a multi-entry queue is drained one call at a time
     * (used to script a retry sequence such as "500 then 200").
     */
    private static final class FakeHttpFetcher implements IHttpFetcher {

        private final Map<URI, Deque<FetchEvent>> queues = new HashMap<>();
        private final Map<URI, Integer> fetchCounts = new HashMap<>();
        private final List<URI> requested = new ArrayList<>();

        void respond(URI uri, HttpFetchResult result) {
            queues.computeIfAbsent(uri, key -> new ArrayDeque<>()).add(() -> result);
        }

        void throwIo(URI uri, IOException exception) {
            queues.computeIfAbsent(uri, key -> new ArrayDeque<>())
                    .add(
                            () -> {
                                throw exception;
                            });
        }

        void throwRuntime(URI uri, RuntimeException exception) {
            queues.computeIfAbsent(uri, key -> new ArrayDeque<>())
                    .add(
                            () -> {
                                throw exception;
                            });
        }

        @Override
        public HttpFetchResult fetch(HttpFetchRequest request) throws IOException {
            requested.add(request.uri());
            fetchCounts.merge(request.uri(), 1, Integer::sum);
            Deque<FetchEvent> queue = queues.get(request.uri());
            if (queue == null || queue.isEmpty()) {
                throw new AssertionError("no fake response queued for " + request.uri());
            }
            FetchEvent event = queue.size() > 1 ? queue.poll() : queue.peek();
            return event.produce();
        }

        List<URI> requested() {
            return requested;
        }

        int fetchCount(URI uri) {
            return fetchCounts.getOrDefault(uri, 0);
        }

        @FunctionalInterface
        private interface FetchEvent {
            HttpFetchResult produce() throws IOException;
        }
    }

    /** Returns the stubbed extraction for a base URI, or an empty result if none was stubbed. */
    private static final class FakeHtmlLinkExtractor implements IHtmlLinkExtractor {

        private final Map<URI, LinkExtractionResult> results = new HashMap<>();

        void stub(URI baseUri, LinkExtractionResult result) {
            results.put(baseUri, result);
        }

        @Override
        public LinkExtractionResult extract(String html, URI baseUri) {
            return results.getOrDefault(
                    baseUri,
                    new LinkExtractionResult(List.of(), Optional.empty(), Optional.empty()));
        }
    }
}

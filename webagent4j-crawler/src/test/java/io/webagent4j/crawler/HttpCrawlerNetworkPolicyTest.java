package io.webagent4j.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.crawler.api.CrawlFailure;
import io.webagent4j.crawler.api.CrawlFailureType;
import io.webagent4j.crawler.api.CrawlRequest;
import io.webagent4j.crawler.api.CrawlResult;
import io.webagent4j.policy.PolicyDecision;
import io.webagent4j.policy.network.INetworkPolicy;
import io.webagent4j.wait.IWaitSleeper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves network-destination governance on {@link HttpCrawler#withNetworkPolicy}: a denied URL -
 * the crawl's own seed or a redirect hop alike - is never fetched, never retried, and never counts
 * against the fetch-identity budget, using only a fake in-memory {@link IHttpFetcher} - never a
 * real network probe.
 */
class HttpCrawlerNetworkPolicyTest {

    @Test
    void deniedSeedResultsInZeroFetches() {
        URI seed = URI.create("https://denied.example.test/");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        INetworkPolicy denyAll = context -> PolicyDecision.deny("test.network.denied");
        HttpCrawler crawler = crawler(fetcher).withNetworkPolicy(denyAll);

        CrawlResult result = crawler.crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(result.pages()).isEmpty();
        assertThat(fetcher.fetchCount(seed)).isZero();
        assertThat(result.failures()).hasSize(1);
        CrawlFailure failure = result.failures().get(0);
        assertThat(failure.type()).isEqualTo(CrawlFailureType.NETWORK_POLICY_DENIED);
        assertThat(result.statistics().fetchedUrls()).isZero();
    }

    @Test
    void allowedSeedFetchesNormally() {
        URI seed = URI.create("https://allowed.example.test/");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respondHtml(seed, "<html><body>ok</body></html>");
        INetworkPolicy allowAll = context -> PolicyDecision.allow("test.network.allowed");
        HttpCrawler crawler = crawler(fetcher).withNetworkPolicy(allowAll);

        CrawlResult result = crawler.crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(result.pages()).hasSize(1);
        assertThat(fetcher.fetchCount(seed)).isEqualTo(1);
    }

    @Test
    void redirectToADeniedTargetFetchesOnlyTheFirstHop() {
        URI seed = URI.create("https://public.example.test/");
        URI deniedTarget = URI.create("https://private.internal.test/");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respondRedirect(seed, deniedTarget);
        INetworkPolicy denyOnlyPrivate =
                context ->
                        context.destination().host().contains("internal")
                                ? PolicyDecision.deny("test.network.private.denied")
                                : PolicyDecision.allow("test.network.allowed");
        HttpCrawler crawler = crawler(fetcher).withNetworkPolicy(denyOnlyPrivate);

        CrawlResult result =
                crawler.crawl(
                        CrawlRequest.builder().seed(seed).maxPages(5).sameHostOnly(false).build());

        assertThat(result.pages()).isEmpty();
        assertThat(fetcher.fetchCount(seed)).isEqualTo(1);
        assertThat(fetcher.fetchCount(deniedTarget)).isZero();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(CrawlFailureType.NETWORK_POLICY_DENIED);
    }

    @Test
    void multiHopRedirectChecksEveryHopIndependently() {
        URI seed = URI.create("https://hop-a.example.test/");
        URI hopB = URI.create("https://hop-b.example.test/");
        URI hopC = URI.create("https://hop-c.denied.test/");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respondRedirect(seed, hopB);
        fetcher.respondRedirect(hopB, hopC);
        INetworkPolicy denyOnlyDenied =
                context ->
                        context.destination().host().contains("denied")
                                ? PolicyDecision.deny("test.network.denied")
                                : PolicyDecision.allow("test.network.allowed");
        HttpCrawler crawler = crawler(fetcher).withNetworkPolicy(denyOnlyDenied);

        CrawlResult result =
                crawler.crawl(
                        CrawlRequest.builder().seed(seed).maxPages(5).sameHostOnly(false).build());

        assertThat(fetcher.fetchCount(seed)).isEqualTo(1);
        assertThat(fetcher.fetchCount(hopB)).isEqualTo(1);
        assertThat(fetcher.fetchCount(hopC)).isZero();
        assertThat(result.failures()).hasSize(1);
    }

    @Test
    void policyEvaluationExceptionResultsInZeroFetchesForThatUrl() {
        URI seed = URI.create("https://boom.example.test/");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        INetworkPolicy throwing =
                context -> {
                    throw new RuntimeException("policy backend unavailable");
                };
        HttpCrawler crawler = crawler(fetcher).withNetworkPolicy(throwing);

        CrawlResult result = crawler.crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(fetcher.fetchCount(seed)).isZero();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).type())
                .isEqualTo(CrawlFailureType.NETWORK_POLICY_EVALUATION_FAILED);
    }

    @Test
    void deniedUrlIsNeverRetried() {
        URI seed = URI.create("https://denied.example.test/");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        RecordingSleeper sleeper = new RecordingSleeper();
        INetworkPolicy denyAll = context -> PolicyDecision.deny("test.network.denied");
        HttpCrawler crawler =
                new HttpCrawler(fetcher, new FakeLinkExtractor(), new HostScopePolicy(), sleeper)
                        .withNetworkPolicy(denyAll);

        crawler.crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(sleeper.sleeps).isEmpty();
        assertThat(fetcher.fetchCount(seed)).isZero();
    }

    @Test
    void deniedRedirectTargetDoesNotConsumeFetchIdentityBudget() {
        URI seed = URI.create("https://public.example.test/");
        URI deniedTarget = URI.create("https://private.internal.test/");
        URI otherSeed = URI.create("https://other.example.test/");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respondRedirect(seed, deniedTarget);
        fetcher.respondHtml(otherSeed, "<html><body>ok</body></html>");
        INetworkPolicy denyOnlyPrivate =
                context ->
                        context.destination().host().contains("internal")
                                ? PolicyDecision.deny("test.network.private.denied")
                                : PolicyDecision.allow("test.network.allowed");
        HttpCrawler crawler = crawler(fetcher).withNetworkPolicy(denyOnlyPrivate);

        CrawlResult result =
                crawler.crawl(
                        CrawlRequest.builder()
                                .seed(seed)
                                .seed(otherSeed)
                                .maxPages(2)
                                .sameHostOnly(false)
                                .build());

        // Only "seed" and "otherSeed" ever claimed fetch identity - the denied redirect target
        // never did, so the budget was never spent on a request that was never sent.
        assertThat(result.statistics().fetchedUrls()).isEqualTo(2);
        assertThat(result.pages()).hasSize(1);
        assertThat(result.pages().get(0).finalUrl()).isEqualTo(otherSeed);
    }

    @Test
    void unconfiguredNetworkPolicyLeavesExistingBehaviorUnchanged() {
        URI seed = URI.create("https://example.test/");
        FakeHttpFetcher fetcher = new FakeHttpFetcher();
        fetcher.respondHtml(seed, "<html><body>ok</body></html>");
        HttpCrawler crawler = crawler(fetcher); // no withNetworkPolicy(...) call

        CrawlResult result = crawler.crawl(CrawlRequest.builder().seed(seed).maxPages(5).build());

        assertThat(result.pages()).hasSize(1);
    }

    private static HttpCrawler crawler(IHttpFetcher fetcher) {
        return new HttpCrawler(
                fetcher, new FakeLinkExtractor(), new HostScopePolicy(), duration -> {});
    }

    private static final class RecordingSleeper implements IWaitSleeper {
        private final List<Duration> sleeps = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
        }
    }

    private static final class FakeLinkExtractor implements IHtmlLinkExtractor {
        @Override
        public LinkExtractionResult extract(String html, URI baseUri) {
            return new LinkExtractionResult(
                    List.of(), java.util.Optional.empty(), java.util.Optional.empty());
        }
    }

    /** Minimal fake fetcher - never touches the real network. */
    private static final class FakeHttpFetcher implements IHttpFetcher {
        private final Map<URI, Deque<HttpFetchResult>> queues = new HashMap<>();
        private final Map<URI, Integer> fetchCounts = new HashMap<>();

        void respondHtml(URI uri, String html) {
            enqueue(
                    uri,
                    new HttpFetchResult(
                            uri,
                            200,
                            Map.of(),
                            html.getBytes(StandardCharsets.UTF_8),
                            "text/html",
                            Duration.ZERO));
        }

        void respondRedirect(URI from, URI to) {
            enqueue(
                    from,
                    new HttpFetchResult(
                            from,
                            302,
                            Map.of("Location", List.of(to.toString())),
                            new byte[0],
                            "text/html",
                            Duration.ZERO));
        }

        private void enqueue(URI uri, HttpFetchResult result) {
            queues.computeIfAbsent(uri, key -> new ArrayDeque<>()).add(result);
        }

        @Override
        public HttpFetchResult fetch(HttpFetchRequest request) throws IOException {
            fetchCounts.merge(request.uri(), 1, Integer::sum);
            Deque<HttpFetchResult> queue = queues.get(request.uri());
            if (queue == null || queue.isEmpty()) {
                throw new AssertionError("test bug: no fake response queued for " + request.uri());
            }
            return queue.size() > 1 ? queue.poll() : queue.peek();
        }

        int fetchCount(URI uri) {
            return fetchCounts.getOrDefault(uri, 0);
        }
    }
}
